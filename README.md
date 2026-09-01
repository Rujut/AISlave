# AISlave Android Personal Action Agent

This repository starts the Android-only personal action agent described in the planning chat.

Milestone 1 is intentionally small and testable:

1. Grant access to a source folder.
2. Grant access to a destination folder.
3. Select one file from the source folder.
4. Move it using Android Storage Access Framework.
5. Verify the result.
6. Record the transaction.
7. Undo the latest move when possible.

Milestone 2 adds the first command interpreter:

1. Choose one global storage access mode: Full, Limited, or Denied.
2. Type a command such as `Move assignment.pdf to College`.
3. The app interprets it as a structured filesystem action.
4. The app recursively searches granted storage for similar source files.
5. If one clear match exists, the app creates a plan directly.
6. If multiple similar files exist, the user selects the correct source file.
7. The app resolves the destination folder from the command.
8. The app shows a plan with tool, risk, access, verification, and undo information.
9. The user confirms the plan.
10. The app executes the verified filesystem tool after confirmation.

Phase 3 adds an automatic backend-backed interpreter. The backend holds the OpenAI key in `OPENAI_API_KEY`; the Android APK receives only a build-time backend URL. The model only returns structured intent JSON; it never receives file contents and never executes filesystem actions directly. If the backend is off, or if the response fails validation, the app uses the local parser.

## Current Phase Completion

- Phase 0: Tool specifications are defined in docs and `V1ToolRegistry`.
- Phase 1: Android Compose foundation is implemented.
- Phase 2: Core file tools are implemented in `FileActionRepository`.
- Phase 3: Hybrid AI interpreter is implemented through `HybridFileCommandInterpreter`, `BackendFileCommandInterpreter`, the Node backend, and local fallback.
- Phase 4: Plan-before-execute flow is implemented through `AgentPlan`.

Example commands:

```text
Move assignment.pdf to College
Can you please move my assignemnt pdf into college folder
Shift the marks file to semester 4
Put invoice pdf in bills
Copy marks.pdf from Documents to College
Rename old.txt in Documents to new.txt
Delete temp.zip from Downloads
Create folder Rrr in Documents
Find assignment in Documents
```

Access modes:

- Full: opens Android's All files access settings for this app, then searches internal storage if granted.
- Limited: opens Android's folder picker; the selected folder becomes the global scope for both source and destination.
- Denied: clears active storage access and blocks command execution.

## Project Layout

- `android-app/`: Kotlin + Jetpack Compose Android app.
- `backend/`: Node HTTP service that calls OpenAI without exposing the API key to Android.
- `docs/architecture.md`: Product and execution architecture.
- `docs/tools.md`: Tool contract for current and future AI tools.
- `docs/permissions.md`: Android permission model.
- `docs/security.md`: Safety rules and V1 exclusions.

## Open In Android Studio

Open this directory as a Gradle project:

```text
/home/n-rujut-pavan-kumar/Documents/AISlave
```

The local machine used to scaffold this project does not currently have Java or Gradle installed, so the first compile should be done from Android Studio or from a terminal with JDK 17 and Gradle available.

## Build

Use the project wrapper:

```bash
./gradlew assembleDebug
```

Do not use a system `gradle` command for this project.

For a production APK, inject your hosted backend URL at build time:

```bash
./gradlew assembleRelease -PAISLAVE_BACKEND_URL=https://api.yourdomain.com
```
