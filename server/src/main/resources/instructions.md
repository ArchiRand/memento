memento is the long-term memory of the user's projects: markdown documents kept in a local git repository and shared between sessions.

## Loading
- At session start, after /clear and after compaction a memento hook injects the core documents of the current project into the context under a "# memento: <project>" heading. Do not call read_context when that heading is present; call it only when it is missing or you need another project.
- If the hook lists core documents under "Not loaded (over budget)", read them with read_documents right away, before answering: they hold the current status.
- The project is resolved from the working directory. Pass `project` only for a project without a code directory (for example a study topic) or to look into another project.
- Read non-core documents on demand with read_documents when the task touches their topic.

## When to write
Update memory right away, not at the end, when something worth keeping happens:
- an architectural or design decision and its reason;
- a completed stage of a task, a change of approach, a found blocker;
- a non-obvious fact about the code or the environment that cost effort to find.
Before finishing a substantial piece of work, bring activeTask.md up to date: it is the only place the next session learns the status from.
Do not store what the repository already records (code structure, git history) or what matters only to the current conversation.

## How to write
- Change an existing document with edit_document (exact oldText → newText pairs, several in one call). Rewrite a whole document with write_document only when most of it changes. Use append_content for logs such as progress.md.
- Pass expectedRevision from your last read. On "revision mismatch" re-read the document, merge your change into the current text and retry; never blindly overwrite.
- Link related documents with [[name]] (without .md), for example "details in [[moneyBoxToolMigration]]".
- Tags: at least 2, lowercase English, consistent across documents (`database`, not `db`).
- core: true only for context.md (goal, stack, constraints) and activeTask.md. Keep core small: the hook loads at most 9 000 characters of core documents, a core document that does not fit costs an extra read at session start. When a tool reports the core budget warning, move stable sections (environment pitfalls, measurements, finished work) into non-core documents and link them.

## Recommended documents
- context.md (core): project goal, stack, constraints and agreements.
- activeTask.md (core): sections Current Tasks (several are fine, each with its repository or branch), Next Steps (each tied to a task), Blockers, Open Tasks, Recently Completed. Move completed items older than two weeks into activeTaskArchive.md.
- progress.md: append-only log of done work.
- architecture.md: decisions and their reasons.

## Empty memory
If the hook says there is no memory for the project and the user works on it, offer to create context.md and activeTask.md. Do not create them without the user's consent.

## History
Every write is a git commit. history(name) lists versions, history(name, commit) returns an old version; use it to restore lost content or when the user asks what changed.
