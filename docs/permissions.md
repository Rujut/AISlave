# Permission Model

The app supports one global storage access decision.

## Access Modes

- Full: Android All files access using `MANAGE_EXTERNAL_STORAGE`. This is powerful and may not be Play Store-friendly without a valid file-management use case.
- Limited: Android Storage Access Framework tree grant. The selected folder becomes the global scope for both source and destination.
- Denied: No storage access; commands cannot execute.

## Folder Access

For Limited access, the user explicitly selects:

- Storage root or another broad parent folder

The old separate Source and Destination folder access section has been removed. The planner searches within the single granted scope for both the source file and destination folder mentioned in the command.

Android may restrict some roots or system folders in the picker. The app must work only inside the tree the user successfully grants.

The app persists URI grants using:

- `Intent.FLAG_GRANT_READ_URI_PERMISSION`
- `Intent.FLAG_GRANT_WRITE_URI_PERMISSION`
- `Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION`

This lets the agent work only inside folders the user has approved.

## Principle

The product language should be:

> I can perform actions your Android device and connected apps allow, using only the access you grant.

Avoid claiming unrestricted phone control.

## Network

Phase 3 adds `android.permission.INTERNET` only for optional backend AI command interpretation. Filesystem execution still happens locally through the app's tool engine after permission checks and confirmation.
