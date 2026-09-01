# Tool Specification

Every tool must define:

- `name`: Stable tool identifier.
- `description`: User-facing behavior.
- `inputs`: Typed input contract.
- `requiredAccess`: Android permissions or user-granted URI access.
- `risk`: Low, medium, or high.
- `requiresConfirmation`: Whether user confirmation is required.
- `canUndo`: Whether rollback is supported.
- `verification`: How success is proven.

## V1 Tool Matrix

| Tool | Implemented | Access | Risk | Confirm | Undo | Verification |
| --- | --- | --- | --- | --- | --- | --- |
| `search_files` | Yes | SAF folder read | Low | No | No | Matching files returned from folder listing |
| `move_file` | Yes | SAF source read/write + destination write | Medium | Yes | Yes | Destination exists, source absent |
| `copy_file` | Yes | SAF source read + destination write | Low | No | Yes | Destination copy exists |
| `rename_file` | Yes | SAF file write | Medium | Yes | Yes | Renamed file exists |
| `create_folder` | Yes | SAF parent folder write | Low | No | Yes | Created directory exists |
| `delete_file` | Yes | SAF file write | High | Yes | No | File absent |
| `read_excel` | Spec only | SAF XLSX read | Low | No | No | Workbook parsed |
| `write_excel` | Spec only | SAF XLSX read/write | Medium | Yes | Yes | Workbook reopens and cells match |
| `create_calendar_event` | Spec only | Calendar write permission | Medium | Yes | Yes | Event read back from provider |

## Milestone 1 Tool

### `move_file`

- `description`: Move a single document from a user-granted source folder to a user-granted destination folder.
- `inputs`: `sourceFileUri`, `destinationFolderUri`.
- `requiredAccess`: Persisted read/write URI access for both folders via Storage Access Framework.
- `risk`: Medium.
- `requiresConfirmation`: Yes.
- `canUndo`: Yes, if the destination file remains available and no naming conflict blocks rollback.
- `verification`: Destination file exists and source file no longer exists.

Implementation note: Storage Access Framework does not guarantee direct atomic moves across providers, so Milestone 1 uses copy, flush, then delete.

## Milestone 2 Planner

### `NaturalLanguageFileCommandParser`

- `description`: Parse local-language filesystem commands into structured tool intents.
- `examples`: `Move assignment.pdf to College`, `Copy marks.pdf from Documents to College`, `Rename old.txt to new.txt`, `Delete temp.zip from Downloads`, `Create folder Rrr in Documents`, `Find assignment in Documents`.
- `outputs`: action, file query, optional source hint, optional destination hint, optional new name, optional folder name.
- `execution`: Does not touch files directly. It creates an `AgentPlan` for write operations, or returns search matches for read-only commands.
- `limitations`: English/code-mixed command shell only for now; no cloud LLM and no multi-step autonomous organization yet.

### `AgentPlan`

- `description`: Structured plan shown before execution.
- `contains`: Title, steps, tool name, risk, required access, undo support, action, selected file, destination folder, target folder, new name, or folder name as needed.
- `rule`: Execution starts only after user confirmation.
