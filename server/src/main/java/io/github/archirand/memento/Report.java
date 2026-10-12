package io.github.archirand.memento;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Self-contained HTML health report of the whole vault: ~/.memento/report.html.
 */
final class Report
{
    static final int WEEKS = 12;
    private static final Pattern HEADING = Pattern.compile("(#{1,3}) +(.+)");
    private static final Pattern LIST_ITEM = Pattern.compile("([-*+]|\\d+\\.) +(\\[[ xX]] +)?");
    private static final int ITEM = 120;
    private static final int LINE = 200;

    private Report()
    {
    }

    /**
     * Writes the report, prints its path, optionally opens it in the browser.
     *
     * @return process exit code
     */
    static int run(Vault vault, Clock clock, boolean open)
    {
        try
        {
            Path file = write(vault, clock);
            System.out.println(file);
            if (open)
            {
                open(file);
            }
            return 0;
        }
        catch (IOException e)
        {
            System.err.println("memento report: " + e.getMessage());
            return 1;
        }
    }

    static Path write(Vault vault, Clock clock) throws IOException
    {
        Files.createDirectories(vault.root);
        Path file = vault.root.resolve("report.html");
        Files.writeString(file, html(vault, clock));
        return file;
    }

    /** One changed file in one commit. */
    record Change(LocalDate date, String path)
    {
        String project()
        {
            int slash = path.indexOf('/');
            return slash < 0 ? "" : path.substring(0, slash);
        }
    }

    static List<Change> changes(Vault vault)
    {
        String log = Git.tryRun(vault.root, "log", "--date=short", "--format=@%ad", "--name-only").orElse("");
        List<Change> changes = new ArrayList<>();
        LocalDate date = null;
        for (String line : log.lines().toList())
        {
            if (line.startsWith("@"))
            {
                date = LocalDate.parse(line.substring(1));
            }
            else if (!line.isBlank() && date != null)
            {
                changes.add(new Change(date, line));
            }
        }
        return changes;
    }

    private static void open(Path file)
    {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        List<String> command = os.contains("mac") ? List.of("open", file.toString())
                             : os.contains("win") ? List.of("cmd", "/c", "start", "", file.toString())
                             : List.of("xdg-open", file.toString());
        try
        {
            new ProcessBuilder(command).start();
        }
        catch (IOException e)
        {
            System.err.println("could not open the browser, open the file by hand");
        }
    }
    static String html(Vault vault, Clock clock) throws IOException
    {
        DocStore store = new DocStore(vault, clock);
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        List<Change> changes = changes(vault);
        Map<String, List<Doc>> projects = new LinkedHashMap<>();
        for (String key : store.projects())
        {
            projects.put(key, store.list(key));
        }
        StringBuilder html = new StringBuilder(HEAD);
        html.append("<h1>memento</h1><p class=muted>").append(esc(vault.root.toString())).append(" · ").append(today).append("</p>");
        html.append("<h2>Activity, last ").append(WEEKS).append(" weeks</h2>").append(heatmap(changes, change -> true, today));
        html.append("<h2>Projects</h2><div class=scroll><table><tr><th>Project</th><th>Documents</th><th>Size</th>")
            .append("<th>Core budget</th><th>Last update</th><th>Warnings</th></tr>");
        projects.forEach((key, docs) -> html.append(projectRow(key, docs, now)));
        html.append("</table></div>");
        projects.forEach((key, docs) -> html.append(projectSection(key, docs, changes, today, now)));
        return html.append("</body></html>").toString();
    }

    private static String projectRow(String key, List<Doc> docs, Instant now)
    {
        int core = Hygiene.coreChars(docs);
        int percent = Math.min(100, core * 100 / Hygiene.CORE_BUDGET);
        String last = docs.stream().map(Doc::modifiedAt).filter(Objects::nonNull).max(Comparator.naturalOrder())
                          .map(at -> LocalDate.ofInstant(at, ZoneOffset.UTC).toString()).orElse("—");
        return "<tr><td><a href=\"#" + esc(key) + "\">" + esc(key) + "</a></td><td>" + docs.size()
               + "</td><td>" + kb(docs.stream().mapToInt(Doc::size).sum())
               + "</td><td><div class=bar><b class=\"" + (core > Hygiene.CORE_BUDGET ? "over" : "") + "\" style=\"width:" + percent + "%\"></b></div>"
               + "<span class=muted>" + core + " / " + Hygiene.CORE_BUDGET + "</span></td><td>" + last
               + "</td><td class=warn>" + esc(String.join("; ", Hygiene.warnings(docs, now))) + "</td></tr>";
    }

    private static String projectSection(String key, List<Doc> docs, List<Change> changes, LocalDate today, Instant now)
    {
        StringBuilder out = new StringBuilder("<h2 id=\"").append(esc(key)).append("\">").append(esc(key)).append("</h2>");
        out.append(heatmap(changes, change -> change.project().equals(key), today));
        Map<String, Integer> tags = new TreeMap<>();
        docs.forEach(doc -> doc.tags().forEach(tag -> tags.merge(tag, 1, Integer::sum)));
        tags.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(20)
            .forEach(e -> out.append("<span class=chip>").append(esc(e.getKey())).append(" ×").append(e.getValue()).append("</span>"));
        out.append("<div class=scroll><table><tr><th>Document</th><th>Tags</th><th>Core</th><th>Size</th><th>Updated</th><th>Versions</th></tr>");
        for (Doc doc : docs)
        {
            String path = key + "/" + doc.name();
            long versions = changes.stream().filter(change -> change.path().equals(path)).count();
            out.append("<tr><td>").append(esc(doc.name())).append("</td><td>").append(esc(String.join(", ", doc.tags())))
               .append("</td><td>").append(doc.core() ? "✓" : "").append("</td><td>").append(kb(doc.size()))
               .append("</td><td>").append(Hygiene.age(doc, now)).append("</td><td>").append(versions).append("</td></tr>");
        }
        out.append("</table></div><h3>Contents</h3>");
        docs.forEach(doc -> out.append(contents(doc)));
        return out.toString();
    }

    /** A markdown heading and its own text up to the next heading; level 0 is the text before the first heading. */
    record Section(int level, String title, String text)
    {
    }

    /**
     * Splits the body at headings of levels 1..3 outside code fences.
     */
    static List<Section> sections(String body)
    {
        List<Section> sections = new ArrayList<>();
        int level = 0;
        String title = "";
        StringBuilder text = new StringBuilder();
        boolean fence = false;
        for (String line : body.lines().toList())
        {
            fence ^= line.startsWith("```");
            Matcher m = HEADING.matcher(line);
            if (!fence && m.matches())
            {
                if (level > 0 || !text.toString().isBlank())
                {
                    sections.add(new Section(level, title, text.toString()));
                }
                level = m.group(1).length();
                title = m.group(2).strip();
                text.setLength(0);
            }
            else
            {
                text.append(line).append('\n');
            }
        }
        if (level > 0 || !text.toString().isBlank())
        {
            sections.add(new Section(level, title, text.toString()));
        }
        return sections;
    }

    /**
     * What the section holds, one line per top-level list item (its bold lead if it has one); without items, its first line.
     */
    static List<String> gist(String text)
    {
        List<String> items = new ArrayList<>();
        for (String line : text.lines().toList())
        {
            Matcher m = LIST_ITEM.matcher(line);
            if (m.lookingAt())
            {
                String item = line.substring(m.end()).strip();
                int bold = item.startsWith("**") ? item.indexOf("**", 2) : -1;
                items.add(cut(bold > 2 ? item.substring(2, bold) : item.replace("**", ""), ITEM));
            }
        }
        if (items.isEmpty())
        {
            text.lines().map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("```")).findFirst()
                .ifPresent(line -> items.add(cut(line.replace("**", ""), LINE)));
        }
        return items;
    }

    private static String cut(String text, int max)
    {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    private static String contents(Doc doc)
    {
        StringBuilder out = new StringBuilder("<details class=doc><summary><b>").append(esc(doc.name())).append("</b> <span class=muted>")
                .append(kb(doc.size())).append("</span></summary>");
        for (Section section : sections(doc.body()))
        {
            String title = section.level() == 0 ? "(intro)" : section.title();
            out.append("<details class=\"sec h").append(section.level()).append("\"><summary><b>").append(esc(title)).append("</b>");
            gist(section.text()).forEach(line -> out.append("<span class=gist>").append(esc(line)).append("</span>"));
            out.append("</summary><pre>").append(esc(section.text().strip())).append("</pre></details>");
        }
        return out.append("</details>").toString();
    }

    /**
     * Day cells from the Monday {@link #WEEKS} weeks ago to today, columns are weeks; level 0..4 by number of changes.
     */
    static String heatmap(List<Change> changes, Predicate<Change> filter, LocalDate today)
    {
        LocalDate start = today.minusWeeks(WEEKS).with(DayOfWeek.MONDAY);
        Map<LocalDate, Integer> counts = new TreeMap<>();
        changes.stream()
               .filter(filter)
               .filter(change -> !change.date().isBefore(start))
               .forEach(change -> counts.merge(change.date(), 1, Integer::sum));
        int max = counts.values().stream().max(Integer::compare).orElse(0);
        StringBuilder out = new StringBuilder("<div class=heat>");
        for (LocalDate day = start; !day.isAfter(today); day = day.plusDays(1))
        {
            int count = counts.getOrDefault(day, 0);
            int level = count == 0 ? 0 : 1 + Math.min(3, count * 4 / (max + 1));
            out.append("<i class=l").append(level).append(" title=\"").append(day).append(": ").append(count).append("\"></i>");
        }
        return out.append("</div>").toString();
    }

    static String esc(String text)
    {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String kb(int bytes)
    {
        return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
    }
    private static final String CSS = """
            :root{--bg:#fff;--fg:#1f2328;--muted:#656d76;--line:#d0d7de;--accent:#1a7f37;--warn:#cf222e;
                  --l0:#ebedf0;--l1:#9be9a8;--l2:#40c463;--l3:#30a14e;--l4:#216e39}
            @media (prefers-color-scheme:dark){:root{--bg:#0d1117;--fg:#e6edf3;--muted:#8d96a0;--line:#30363d;--accent:#3fb950;
                  --warn:#f85149;--l0:#161b22;--l1:#0e4429;--l2:#006d32;--l3:#26a641;--l4:#39d353}}
            body{font:14px/1.5 -apple-system,system-ui,"Segoe UI",sans-serif;background:var(--bg);color:var(--fg);max-width:1100px;margin:0 auto;padding:16px}
            a{color:var(--accent)}
            .muted{color:var(--muted)}.warn{color:var(--warn)}
            .scroll{overflow-x:auto}
            table{border-collapse:collapse;width:100%;margin:8px 0 24px}
            th,td{text-align:left;padding:6px 8px;border-bottom:1px solid var(--line);white-space:nowrap}
            .bar{background:var(--l0);border-radius:3px;height:8px;width:160px}
            .bar b{display:block;height:8px;border-radius:3px;background:var(--accent)}.bar b.over{background:var(--warn)}
            .heat{display:grid;grid-template-rows:repeat(7,11px);grid-auto-flow:column;grid-auto-columns:11px;gap:3px;margin:8px 0 16px}
            .heat i{border-radius:2px}
            .l0{background:var(--l0)}.l1{background:var(--l1)}.l2{background:var(--l2)}.l3{background:var(--l3)}.l4{background:var(--l4)}
            .doc{margin:4px 0}.doc>summary{cursor:pointer}
            .sec{margin:4px 0}.sec>summary{cursor:pointer}
            .gist{display:block;margin-left:20px;color:var(--muted)}.gist::before{content:"· "}
            .h1,.h0{margin-left:16px}.h2{margin-left:32px}.h3{margin-left:48px}
            pre{white-space:pre-wrap;overflow-wrap:anywhere;background:var(--l0);padding:8px;border-radius:6px}
            .chip{display:inline-block;border:1px solid var(--line);border-radius:10px;padding:0 8px;margin:2px;font-size:12px}
            """;

    private static final String HEAD = "<!doctype html><html lang=en><head><meta charset=utf-8>"
            + "<meta name=viewport content=\"width=device-width,initial-scale=1\"><title>memento report</title><style>"
            + CSS + "</style></head><body>";
}
