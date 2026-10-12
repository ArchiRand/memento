# memento

Long-term project memory for [Claude Code](https://code.claude.com): markdown documents in a local git repository that Claude reads and updates across sessions.

- **Loads itself.** A SessionStart hook injects the core documents (`context.md`, `activeTask.md`) of the current project at session start, after `/clear` and after compaction.
- **No project ids.** The project is resolved from the working directory: git origin name, then repository root, then folder. Two clones of one repository share memory.
- **History.** Every change is a git commit in `~/.memento`; old versions are one tool call away.
- **Safe edits.** `edit_document` changes exact fragments; `expectedRevision` rejects writes based on stale reads.
- **Plain files.** Open `~/.memento` in any editor, in Obsidian (graph of `[[links]]`) or in a git GUI.

## Install

Requirements: Java 17+ and git on `PATH`.

```
/plugin marketplace add ArchiRand/memento
/plugin install memento@memento
```

Storage location: `~/.memento`. Override with the `MEMENTO_HOME` environment variable.

## Use

Work as usual: Claude reads and writes memory on its own. You can also ask directly: "save this to memory", "what is in activeTask", "show how architecture.md changed".

`/memento:report` builds `~/.memento/report.html`: projects, sizes, core budget, stale documents, activity heatmap, tags.

## Tools

| Tool | Purpose |
|---|---|
| `read_context` | all documents' metadata, core content, warnings |
| `read_documents` | content of the given documents |
| `write_document` | create or fully overwrite |
| `edit_document` | replace exact fragments, all or nothing |
| `append_content` | append to a log |
| `search_by_tags` | documents having all given tags |
| `delete_document` | delete (stays in history) |
| `history` | versions of a document, or its content at a commit |

## Build

```
cd server && mvn package   # tests + plugin/memento.jar
python3 -I scripts/trace_mcp.py   # end-to-end trace of the MCP server before a release
```
