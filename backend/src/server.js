import http from "node:http";

const PORT = Number(process.env.PORT || 8787);
const OPENAI_API_KEY = process.env.OPENAI_API_KEY || "";
const OPENAI_MODEL = process.env.OPENAI_MODEL || "gpt-4.1-mini";
const MAX_BODY_BYTES = 16 * 1024;
const WINDOW_MS = 60 * 1000;
const MAX_REQUESTS_PER_WINDOW = 60;
const rateLimitBuckets = new Map();

const assistantResponseSchema = {
  type: "object",
  additionalProperties: false,
  properties: {
    responseType: { type: "string", enum: ["chat", "command"] },
    chatMessage: { type: ["string", "null"] },
    action: { type: "string", enum: ["move", "copy", "rename", "delete", "create_folder", "search"] },
    fileQuery: { type: ["string", "null"] },
    sourceHint: { type: ["string", "null"] },
    destinationHint: { type: ["string", "null"] },
    newName: { type: ["string", "null"] },
    folderName: { type: ["string", "null"] }
  },
  required: ["responseType", "chatMessage", "action", "fileQuery", "sourceHint", "destinationHint", "newName", "folderName"]
};

const server = http.createServer(async (req, res) => {
  try {
    setCorsHeaders(res);

    if (req.method === "OPTIONS") {
      res.writeHead(204);
      res.end();
      return;
    }

    if (req.method === "GET" && req.url === "/health") {
      sendJson(res, 200, { ok: true });
      return;
    }

    if (req.method !== "POST" || req.url !== "/v1/interpret") {
      sendJson(res, 404, { error: "Not found" });
      return;
    }

    if (!OPENAI_API_KEY) {
      sendJson(res, 500, { error: "OPENAI_API_KEY is not configured on the backend." });
      return;
    }

    const clientId = req.headers["x-forwarded-for"]?.toString().split(",")[0].trim() || req.socket.remoteAddress || "unknown";
    if (!allowRequest(clientId)) {
      sendJson(res, 429, { error: "Too many requests. Try again shortly." });
      return;
    }

    const body = await readJsonBody(req);
    const commandText = cleanString(body.command);
    const toolContext = cleanString(body.toolContext);
    if (!commandText) {
      sendJson(res, 400, { error: "Missing command." });
      return;
    }

    const interpreted = await respondWithOpenAI(commandText, toolContext);
    sendJson(res, 200, {
      ...interpreted,
      provider: "openai",
      model: OPENAI_MODEL
    });
  } catch (error) {
    sendJson(res, 500, { error: error?.message || "Internal server error" });
  }
});

server.listen(PORT, "0.0.0.0", () => {
  console.log(`AISlave backend listening on http://0.0.0.0:${PORT}`);
});

async function respondWithOpenAI(commandText, toolContext) {
  const response = await fetch("https://api.openai.com/v1/responses", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${OPENAI_API_KEY}`,
      "Content-Type": "application/json"
    },
    body: JSON.stringify({
      model: OPENAI_MODEL,
      input: [
        { role: "system", content: systemPrompt(toolContext) },
        { role: "user", content: commandText }
      ],
      text: {
        format: {
          type: "json_schema",
          name: "assistant_response",
          strict: true,
          schema: assistantResponseSchema
        }
      }
    })
  });

  const responseText = await response.text();
  if (!response.ok) {
    throw new Error(`OpenAI HTTP ${response.status}: ${responseText.slice(0, 240)}`);
  }

  const responseJson = JSON.parse(responseText);
  const outputText = extractOutputText(responseJson);
  if (!outputText) {
    throw new Error("OpenAI returned no structured output text.");
  }

  return validateAssistantResponse(JSON.parse(outputText));
}

function systemPrompt(toolContext) {
  return `You are the AI assistant inside an Android personal action app.

Rules:
- You may chat normally for greetings, questions, explanations, brainstorming, and non-filesystem conversation.
- If the user asks for a phone filesystem action, return responseType "command".
- If the user is only chatting, return responseType "chat" and answer naturally in chatMessage.
- Do not claim you completed phone actions. The Android app executes commands only after local permission checks and user confirmation.
- Do not invent files, folders, paths, or Android URIs.
- Output only the JSON object required by the schema.
- Preserve the user's intended file/search phrase, including imperfect spelling.
- If the user mentions source area words such as Documents, Downloads, DCIM, Pictures, Music, Movies, or a named folder, put that name in sourceHint.
- If the user says current folder, current directory, this folder, same folder, or here, set sourceHint to null. That means the app's current granted storage scope, not a folder named "current".
- For move/copy, put the target folder in destinationHint.
- For rename, put the existing file/search phrase in fileQuery and the requested new name in newName.
- For create_folder, put the folder to create in folderName and parent folder in destinationHint when mentioned.
- If the request is just to find/show/list/search, use action search.
- For chat responses, set action to "search" and all command-specific fields to null except chatMessage.

Available tools:
${toolContext || "search_files, move_file, copy_file, rename_file, create_folder, delete_file"}`;
}

function extractOutputText(responseJson) {
  if (typeof responseJson.output_text === "string" && responseJson.output_text.trim()) {
    return responseJson.output_text;
  }

  for (const item of responseJson.output || []) {
    for (const content of item.content || []) {
      if (typeof content.text === "string" && content.text.trim()) {
        return content.text;
      }
    }
  }
  return "";
}

function validateAssistantResponse(raw) {
  const responseType = cleanString(raw.responseType);
  if (responseType === "chat") {
    const chatMessage = cleanNullable(raw.chatMessage);
    if (!chatMessage) {
      throw new Error("Model returned an empty chat message.");
    }
    return { chatMessage };
  }
  if (responseType !== "command") {
    throw new Error("Model returned an unsupported response type.");
  }

  const command = {
    action: cleanString(raw.action),
    fileQuery: cleanNullable(raw.fileQuery),
    sourceHint: normalizeScopeHint(cleanNullable(raw.sourceHint)),
    destinationHint: cleanNullable(raw.destinationHint),
    newName: cleanNullable(raw.newName),
    folderName: cleanNullable(raw.folderName)
  };

  const supported = new Set(["move", "copy", "rename", "delete", "create_folder", "search"]);
  if (!supported.has(command.action)) {
    throw new Error("Model returned an unsupported action.");
  }

  const missing =
    ((command.action === "move" || command.action === "copy") && (!command.fileQuery || !command.destinationHint)) ||
    (command.action === "rename" && (!command.fileQuery || !command.newName)) ||
    ((command.action === "delete" || command.action === "search") && !command.fileQuery) ||
    (command.action === "create_folder" && !command.folderName);

  if (missing) {
    throw new Error(`Model returned missing fields for ${command.action}.`);
  }

  return { command };
}

function readJsonBody(req) {
  return new Promise((resolve, reject) => {
    let raw = "";
    req.on("data", (chunk) => {
      raw += chunk;
      if (Buffer.byteLength(raw) > MAX_BODY_BYTES) {
        reject(new Error("Request body too large."));
        req.destroy();
      }
    });
    req.on("end", () => {
      try {
        resolve(raw ? JSON.parse(raw) : {});
      } catch {
        reject(new Error("Invalid JSON body."));
      }
    });
    req.on("error", reject);
  });
}

function allowRequest(clientId) {
  const now = Date.now();
  const bucket = rateLimitBuckets.get(clientId);
  if (!bucket || now - bucket.startedAt > WINDOW_MS) {
    rateLimitBuckets.set(clientId, { startedAt: now, count: 1 });
    return true;
  }
  bucket.count += 1;
  return bucket.count <= MAX_REQUESTS_PER_WINDOW;
}

function cleanNullable(value) {
  const cleaned = cleanString(value);
  return cleaned || null;
}

function cleanString(value) {
  return typeof value === "string"
    ? value.trim().replace(/^["'`.,;:\s]+|["'`.,;:\s]+$/g, "")
    : "";
}

function normalizeScopeHint(value) {
  const normalized = cleanString(value).toLowerCase().replace(/[^a-z0-9]+/g, " ").trim();
  const currentScopeWords = new Set(["current", "current folder", "current directory", "this folder", "same folder", "here"]);
  return currentScopeWords.has(normalized) ? null : value;
}

function setCorsHeaders(res) {
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
  res.setHeader("Access-Control-Allow-Headers", "Content-Type");
}

function sendJson(res, status, body) {
  res.writeHead(status, { "Content-Type": "application/json" });
  res.end(JSON.stringify(body));
}
