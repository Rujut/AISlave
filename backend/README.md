# AISlave Backend

This small server keeps the OpenAI API key out of the Android APK.

The phone sends only command text and tool descriptions to:

```text
POST /v1/interpret
```

The server returns one validated filesystem command:

```json
{
  "command": {
    "action": "move",
    "fileQuery": "assignment pdf",
    "sourceHint": "Documents",
    "destinationHint": "College",
    "newName": null,
    "folderName": null
  }
}
```

## Production Shape

The backend must run on your server, not on the user's phone. After you deploy it once, users install the APK and use AI prompts directly.

This repository includes a root `render.yaml` Blueprint for Render.

Build the Android APK with your hosted backend URL:

```bash
./gradlew assembleRelease -PAISLAVE_BACKEND_URL=https://api.yourdomain.com
```

The APK will use that backend automatically.

## Run Locally

Create a new OpenAI key after revoking any key that was pasted into chat.

```bash
cd backend
cp .env.example .env
```

Edit `.env`, then run:

```bash
set -a
. ./.env
set +a
npm start
```

Health check:

```bash
curl http://localhost:8787/health
```

Interpret test:

```bash
curl -X POST http://localhost:8787/v1/interpret \
  -H 'Content-Type: application/json' \
  -d '{"command":"move my assignment pdf from Documents to College"}'
```

## Android URL

Android emulator:

```text
http://10.0.2.2:8787
```

Real phone on same Wi-Fi:

```text
http://YOUR_LAPTOP_IP:8787
```

Production should use HTTPS, authentication, persistent quota tracking, and stronger abuse controls.

## Auto Start On A Linux Server

Use `aislave-backend.service.example` as a systemd template, or deploy the included `Dockerfile` to any container host.
