# Android Personal Action Agent Architecture

AISlave is an Android personal action agent. The product should perform actions only through Android-supported APIs and only after the user grants the required access.

## Phase 0: Specification

The project now defines a tool contract before adding broad automation. Each tool declares:

- Input contract
- Required Android access
- Risk level
- Confirmation requirement
- Undo support
- Verification rule

The executable V1 registry is in `V1ToolRegistry`.

## Phase 1: Android Foundation

The Android app foundation is a Kotlin + Jetpack Compose app with:

- Main command screen
- One global storage permission choice: Full, Limited, or Denied
- Broad storage tree access for agent search when granted
- Connected capability display through granted folders
- Matching source file candidate list from the prompt
- Status/result panel
- Latest transaction display
- Undo action

## Phase 2: Tool Engine

Implemented file tool engine:

- `searchFiles()`
- `moveFile()`
- `copyFile()`
- `renameFile()`
- `createFolder()`
- `deleteFile()`

The UI currently executes `moveFile()` through the planning flow. The other file tools are available in the repository layer for the next command intents.

Specified but not implemented yet:

- Excel read/write/formula/format/save tools
- Calendar event tools
- Alarm/reminder tools
- Contact tools
- Image/PDF tools

## Phase 3: AI Provider Boundary

Phase 3 uses a hybrid command interpreter:

- `BackendFileCommandInterpreter` calls your backend when a backend URL is enabled.
- `LocalFileCommandInterpreter` keeps the deterministic parser available for offline use and fallback.
- `HybridFileCommandInterpreter` accepts backend output only when it validates into one known file action.

```text
Command text
  -> Android backend AI client
  -> backend server
  -> OpenAI structured output
  -> structured filesystem command
  -> file resolver
  -> agent plan
```

The backend keeps `OPENAI_API_KEY` out of the APK. The remote model receives available tool specs and returns only structured intent fields. It does not receive file contents, folder listings, or Android URIs.

Non-action messages return a chat response. Action messages return command intent and continue through the local resolver, planner, confirmation, execution, and verifier.

## Phase 4: Planning

Commands now produce a plan before execution:

```text
USER REQUEST
      ↓
AI INTERPRETER
      ↓
PLAN
      ↓
PERMISSION CHECK
      ↓
USER CONFIRMATION
      ↓
EXECUTE
      ↓
VERIFY + TRANSACTION
```

For example:

```text
Move assignment.pdf to College
```

creates a plan showing the selected file, destination, tool name, risk, verification, and undo support. The file is moved only after the user taps Confirm.

## Milestone 1 Scope

Milestone 1 proves the execution foundation without AI:

1. User grants access to a source folder.
2. User grants access to a destination folder.
3. User selects a file.
4. App moves the file.
5. App verifies the destination file exists and the original no longer exists.
6. App records a transaction.
7. App offers undo for the latest safe transaction.

## Milestone 2 Scope

Milestone 2 proves the first planning layer without a remote LLM:

1. User enters a text command.
2. Local parser detects a move-file intent.
3. Parser extracts a file query and optional destination hint.
4. Tool router recursively searches granted storage for similar source files.
5. If one clear match exists, the app creates a plan directly.
6. If multiple similar matches exist, the user selects the correct source file.
7. Tool router resolves the destination folder from the command.
8. App shows a plan and waits for confirmation.
9. Existing `move_file` tool performs the action.
10. Existing verifier and transaction log remain responsible for correctness.

## Core Flow

```text
User action
  -> permission check
  -> tool execution
  -> verification
  -> transaction log
  -> result / undo
```

## Future AI Flow

```text
Natural language
  -> planner
  -> tool router
  -> permission engine
  -> tool execution
  -> verifier
  -> result / undo
```

The AI must never access Android resources directly. It receives a tool registry and returns structured tool calls.

Phase 3 uses a backend-mediated OpenAI structured output interpreter with `NaturalLanguageFileCommandParser` as the local fallback.
