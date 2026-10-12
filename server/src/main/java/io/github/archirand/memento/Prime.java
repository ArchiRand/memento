package io.github.archirand.memento;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SessionStart hook: injects core documents of the current project into the session.
 */
final class Prime
{
    private static final ObjectMapper JSON = new ObjectMapper();

    private Prime()
    {
    }

    /**
     * Reads hook input JSON, prints hook output JSON, journals the run. Never throws: on any error prints nothing.
     */
    static void run(InputStream in, PrintStream out, Vault vault, Clock clock)
    {
        CallLog log = new CallLog(vault, clock, "prime");
        Map<String, Object> entry = new LinkedHashMap<>();
        try
        {
            JsonNode input = JSON.readTree(in);
            Path cwd = Path.of(input.path("cwd").asText(System.getProperty("user.dir")));
            entry.put("cwd", cwd.toString());
            Project project = ProjectResolver.resolve(cwd, Registry.load(vault.root));
            entry.put("project", project.key());
            entry.put("inGit", project.inGit());
            List<Doc> docs = new DocStore(vault, clock).list(project.key());
            String context = render(project, docs, clock.instant());
            entry.put("documents", docs.size());
            entry.put("chars", context.length());
            entry.put("notLoaded", docs.stream().filter(Doc::core).map(Doc::name)
                                       .filter(name -> !context.contains("## " + name + "\n\n")).toList());
            if (!context.isEmpty())
            {
                ObjectNode output = JSON.createObjectNode();
                output.putObject("hookSpecificOutput")
                      .put("hookEventName", "SessionStart")
                      .put("additionalContext", context);
                out.println(JSON.writeValueAsString(output));
            }
            entry.put("outcome", "ok");
        }
        catch (Exception e)
        {
            entry.put("outcome", "error");
            entry.put("error", String.valueOf(e));
            System.err.println("memento prime: " + e);
        }
        log.write(entry);
    }
    /**
     * Hook context: header, warnings, core documents within the budget, not loaded core, other documents.
     */
    static String render(Project project, List<Doc> docs, Instant now)
    {
        if (docs.isEmpty())
        {
            return project.inGit()
                   ? "No memento memory for `" + project.key() + "` yet. If you work on this project, offer the user to create context.md and activeTask.md."
                   : "";
        }
        StringBuilder out = new StringBuilder("# memento: ").append(project.key()).append("\n\n");
        List<String> warnings = Hygiene.warnings(docs, now);
        if (!warnings.isEmpty())
        {
            out.append("## Warnings\n");
            warnings.forEach(warning -> out.append("- ").append(warning).append('\n'));
            out.append('\n');
        }
        List<String> notLoaded = new ArrayList<>();
        int coreChars = 0;
        for (Doc doc : docs.stream().filter(Doc::core).sorted(Doc.PRIME_ORDER).toList())
        {
            String block = doc.contextBlock();
            if (coreChars + block.length() <= Hygiene.CORE_BUDGET)
            {
                out.append(block);
                coreChars += block.length();
            }
            else
            {
                notLoaded.add(doc.name());
            }
        }
        if (!notLoaded.isEmpty())
        {
            out.append("## Not loaded (over budget) — call read_documents for these now, before answering\n");
            notLoaded.forEach(name -> out.append("- ").append(name).append('\n'));
            out.append('\n');
        }
        List<Doc> others = docs.stream().filter(doc -> !doc.core()).toList();
        if (!others.isEmpty())
        {
            out.append("## Other documents\n");
            for (int i = 0; i < others.size(); i++)
            {
                Doc doc = others.get(i);
                String line = "- " + doc.name() + " " + doc.tags() + " — " + Hygiene.age(doc, now) + "\n";
                if (out.length() + line.length() > Hygiene.BUDGET - 60)
                {
                    out.append("- … and ").append(others.size() - i).append(" more (read_context)\n");
                    break;
                }
                out.append(line);
            }
        }
        return out.toString();
    }
}
