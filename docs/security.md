# Security and Safety Notes

## Rules

- No silent destructive action.
- File deletes require confirmation.
- File moves require explicit user action in Milestone 1.
- AI tools must remain behind structured contracts.
- Backend AI may interpret commands, but it must not receive file contents, folder listings, or executable Android URIs.
- OpenAI API keys must live only in backend environment variables, never in Android code, APKs, docs, or app preferences.
- Invalid or unsupported AI output must fail closed or fall back to the local parser.
- Each write operation must verify the result.
- Undo is offered only when the app can reasonably execute it.

## Sensitive Capabilities

Do not include SMS, calls, notifications, accessibility automation, or background clipboard access in V1. These require separate policy and technical review.
