package io.github.archirand.memento;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.archirand.memento.DocStore.Edit;
import io.github.archirand.memento.DocStore.Saved;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The MCP tools of memento.
 */
final class Tools
{
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, Object> PROJECT = Map.of("type", "string",
            "description", "Project key. Omit to use the current project resolved from the working directory.");
    private static final Map<String, Object> NAME = Map.of("type", "string", "description", "Document name, e.g. activeTask.md");
    private static final Map<String, Object> STRINGS = Map.of("type", "array", "items", Map.of("type", "string"));
    /** Tools that fail on an unknown explicit project instead of creating it. */
    private static final Set<String> READ_ONLY = Set.of("read_context", "read_documents", "search_by_tags", "history");
    private static final Map<String, Object> REVISION = Map.of("type", "string",
            "description", "Revision from your last read; the call fails if the document changed since then.");

    private final Vault vault;
    private final DocStore store;
    private final Clock clock;
    private final Path cwd;
    private final CallLog log;

    Tools(Vault vault, Clock clock, Path cwd, CallLog log)
    {
        this.vault = vault;
        this.store = new DocStore(vault, clock);
        this.clock = clock;
        this.cwd = cwd;
        this.log = log;
    }

    List<SyncToolSpecification> all()
    {
        return List.of(readContext(), readDocuments(), writeDocument(), editDocument(),
                       appendContent(), searchByTags(), deleteDocument(), history());
    }

    private SyncToolSpecification readContext()
    {
        return tool("read_context",
                    "Metadata of all documents of the project, full content of the core documents and hygiene warnings. "
                    + "The memento hook already injects core at session start; call this only if it is missing or for another project.",
                    Map.of(), List.of(),
                    (project, args) -> {
                        List<Doc> docs = store.list(project.key());
                        Map<String, Object> out = new LinkedHashMap<>();
                        out.put("project", project.key());
                        out.put("documents", docs.stream().map(Tools::meta).toList());
                        out.put("core", docs.stream().filter(Doc::core).sorted(Doc.PRIME_ORDER)
                                            .map(doc -> Map.of("name", doc.name(), "content", doc.body())).toList());
                        out.put("warnings", Hygiene.warnings(docs, clock.instant()));
                        return out;
                    });
    }

    private SyncToolSpecification readDocuments()
    {
        return tool("read_documents", "Full content and metadata (with revision) of the given documents.",
                    Map.of("names", with(STRINGS, "Document names, e.g. [\"architecture.md\"]")), List.of("names"),
                    (project, args) -> {
                        List<Map<String, Object>> documents = new ArrayList<>();
                        List<String> missing = new ArrayList<>();
                        for (String name : strings(args, "names"))
                        {
                            if (!Doc.NAME.matcher(name).matches())
                            {
                                missing.add(name);
                                continue;
                            }
                            store.read(project.key(), name).ifPresentOrElse(doc -> {
                                Map<String, Object> entry = meta(doc);
                                entry.put("content", doc.body());
                                documents.add(entry);
                            }, () -> missing.add(name));
                        }
                        return Map.of("project", project.key(), "documents", documents, "missing", missing);
                    });
    }

    private SyncToolSpecification searchByTags()
    {
        return tool("search_by_tags", "Metadata of the documents that have ALL the given tags.",
                    Map.of("tags", with(STRINGS, "Tags, all of which must be present")), List.of("tags"),
                    (project, args) -> {
                        List<String> tags = strings(args, "tags");
                        return Map.of("project", project.key(), "documents",
                                      store.list(project.key()).stream().filter(doc -> doc.tags().containsAll(tags)).map(Tools::meta).toList());
                    });
    }

    private SyncToolSpecification history()
    {
        return tool("history", "Without commit: versions of the document, newest first (deleted documents too). With commit: the document file as of that commit.",
                    Map.of("name", NAME,
                           "commit", Map.of("type", "string", "description", "Commit sha from the version list"),
                           "limit", Map.of("type", "integer", "description", "How many versions to list, default 10")),
                    List.of("name"),
                    (project, args) -> {
                        String name = string(args, "name");
                        String commit = string(args, "commit");
                        if (commit != null)
                        {
                            return Map.of("name", name, "commit", commit, "content", store.contentAt(project.key(), name, commit));
                        }
                        Object limit = args.get("limit");
                        return Map.of("name", name, "versions",
                                      store.history(project.key(), name, limit instanceof Number n ? n.intValue() : 10));
                    });
    }
    private SyncToolSpecification writeDocument()
    {
        return tool("write_document",
                    "Create a document or overwrite it completely. To change part of an existing document use edit_document instead.",
                    Map.of("name", NAME,
                           "content", Map.of("type", "string", "description", "Full markdown content"),
                           "tags", with(STRINGS, "At least 2 lowercase English tags"),
                           "core", Map.of("type", "boolean", "description", "Load automatically at session start (only context.md and activeTask.md). Omit to keep the current flag; false for a new document"),
                           "expectedRevision", REVISION),
                    List.of("name", "content", "tags"),
                    (project, args) -> {
                        return saved(project, store.write(project, string(args, "name"), text(args, "content"), strings(args, "tags"),
                                                          args.get("core") instanceof Boolean b ? b : null, string(args, "expectedRevision")));
                    });
    }

    private SyncToolSpecification editDocument()
    {
        Map<String, Object> edit = Map.of("type", "object",
                                          "properties", Map.of("oldText", Map.of("type", "string", "description", "Exact text that occurs exactly once in the document"),
                                                               "newText", Map.of("type", "string", "description", "Replacement, may be empty")),
                                          "required", List.of("oldText", "newText"));
        return tool("edit_document",
                    "Change parts of an existing document: each oldText must occur exactly once. Edits apply in order, all or nothing, as one version. Tags and core stay as they are.",
                    Map.of("name", NAME,
                           "edits", Map.of("type", "array", "items", edit),
                           "expectedRevision", REVISION),
                    List.of("name", "edits"),
                    (project, args) -> {
                        List<Edit> edits = new ArrayList<>();
                        if (args.get("edits") instanceof List<?> list)
                        {
                            for (Object item : list)
                            {
                                Map<?, ?> map = item instanceof Map<?, ?> m ? m : Map.of();
                                edits.add(new Edit(map.get("oldText") instanceof String oldText ? oldText : null,
                                                   map.get("newText") instanceof String newText ? newText : null));
                            }
                        }
                        return saved(project, store.edit(project, string(args, "name"), edits, string(args, "expectedRevision")));
                    });
    }

    private SyncToolSpecification appendContent()
    {
        return tool("append_content",
                    "Append text to the end of a document (logs like progress.md). Creates the document if missing; tags are required then.",
                    Map.of("name", NAME,
                           "content", Map.of("type", "string", "description", "Markdown to append"),
                           "tags", with(STRINGS, "Only for a new document: at least 2 lowercase English tags"),
                           "core", Map.of("type", "boolean", "description", "Only for a new document. Default false")),
                    List.of("name", "content"),
                    (project, args) -> {
                        List<String> tags = args.containsKey("tags") ? strings(args, "tags") : null;
                        return saved(project, store.append(project, string(args, "name"), text(args, "content"), tags,
                                                           args.get("core") instanceof Boolean b ? b : null));
                    });
    }

    private SyncToolSpecification deleteDocument()
    {
        return tool("delete_document", "Delete a document. It stays in history and can be restored.",
                    Map.of("name", NAME), List.of("name"),
                    (project, args) -> {
                        String warning = store.delete(project, string(args, "name"));
                        Map<String, Object> out = new LinkedHashMap<>();
                        out.put("deleted", string(args, "name"));
                        out.put("warnings", warning == null ? List.of() : List.of(warning));
                        return out;
                    });
    }
    private SyncToolSpecification tool(String name, String description, Map<String, Object> properties,
                                       List<String> required, Handler handler)
    {
        Map<String, Object> all = new LinkedHashMap<>(properties);
        all.put("project", PROJECT);
        var schema = new McpSchema.JsonSchema("object", all, required, false, null, null);
        var tool = new McpSchema.Tool(name, null, description, schema, null, null, null);
        return new SyncToolSpecification(tool, (exchange, request) ->
                call(name, handler, request.arguments() == null ? Map.of() : request.arguments()));
    }

    /**
     * Resolves the project, runs the handler, turns the outcome into a tool result and journals the call.
     */
    private McpSchema.CallToolResult call(String tool, Handler handler, Map<String, Object> args)
    {
        long start = System.nanoTime();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("cwd", cwd.toString());
        entry.put("tool", tool);
        entry.put("args", CallLog.shorten(args));
        McpSchema.CallToolResult result;
        try
        {
            Project project = project(args, READ_ONLY.contains(tool));
            entry.put("project", project.key());
            Object out = handler.handle(project, args);
            if (out instanceof Map<?, ?> map)
            {
                if (map.get("revision") != null)
                {
                    entry.put("revision", map.get("revision"));
                }
                if (map.get("warnings") instanceof List<?> warnings && !warnings.isEmpty())
                {
                    entry.put("warnings", warnings);
                }
            }
            entry.put("outcome", "ok");
            result = result(JSON.writeValueAsString(out), false);
        }
        catch (MementoException e)
        {
            entry.put("outcome", "error");
            entry.put("error", e.getMessage());
            result = result(e.getMessage(), true);
        }
        catch (IOException | RuntimeException e)
        {
            entry.put("outcome", "error");
            entry.put("error", String.valueOf(e));
            result = result("memento error: " + e.getMessage(), true);
        }
        entry.put("ms", (System.nanoTime() - start) / 1_000_000);
        log.write(entry);
        return result;
    }

    private static McpSchema.CallToolResult result(String text, boolean error)
    {
        return McpSchema.CallToolResult.builder()
                                       .content(List.of(new McpSchema.TextContent(text)))
                                       .isError(error)
                                       .build();
    }

    /**
     * Explicit key from args, otherwise resolved from the working directory. A read-only tool fails on an unknown explicit key.
     */
    private Project project(Map<String, Object> args, boolean reading) throws IOException
    {
        String key = string(args, "project");
        if (key == null)
        {
            return ProjectResolver.resolve(cwd, Registry.load(vault.root));
        }
        Project project = ProjectResolver.explicit(key);
        List<String> known = store.projects();
        if (reading && !known.contains(key))
        {
            throw new MementoException("unknown project '" + key + "'; known projects: " + String.join(", ", known));
        }
        return project;
    }

    private Map<String, Object> saved(Project project, Saved saved) throws IOException
    {
        List<String> warnings = new ArrayList<>(Hygiene.warnings(store.list(project.key()), clock.instant()));
        if (saved.gitWarning() != null)
        {
            warnings.add(saved.gitWarning());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project", project.key());
        out.put("name", saved.doc().name());
        out.put("revision", saved.doc().revision());
        out.put("warnings", warnings);
        return out;
    }

    private static Map<String, Object> meta(Doc doc)
    {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("name", doc.name());
        meta.put("tags", doc.tags());
        meta.put("core", doc.core());
        meta.put("size", doc.size());
        meta.put("lastModified", doc.lastModified());
        meta.put("revision", doc.revision());
        return meta;
    }

    private static Map<String, Object> with(Map<String, Object> schema, String description)
    {
        Map<String, Object> copy = new LinkedHashMap<>(schema);
        copy.put("description", description);
        return copy;
    }

    /** Trimmed non-blank string argument or null. */
    private static String string(Map<String, Object> args, String key)
    {
        return args.get(key) instanceof String s && !s.isBlank() ? s.strip() : null;
    }

    /** String argument exactly as sent (markdown content must keep its whitespace), empty if absent. */
    private static String text(Map<String, Object> args, String key)
    {
        return args.get(key) instanceof String s ? s : "";
    }

    private static List<String> strings(Map<String, Object> args, String key)
    {
        return args.get(key) instanceof List<?> list ? list.stream().map(String::valueOf).map(String::strip).toList() : List.of();
    }

    @FunctionalInterface
    private interface Handler
    {
        Object handle(Project project, Map<String, Object> args) throws IOException;
    }
}
