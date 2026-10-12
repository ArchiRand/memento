package io.github.archirand.memento;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportTest
{
    @TempDir
    Path tmp;

    @Test
    void reportShowsEveryProjectWithItsDocuments() throws Exception
    {
        Vault vault = new Vault(tmp.resolve("vault"));
        DocStore store = new DocStore(vault, Fixtures.clock());
        store.write(ProjectResolver.explicit("alpha"), "context.md", "a", List.of("context", "alpha"), true, null);
        store.write(ProjectResolver.explicit("beta"), "notes.md", "b", List.of("notes", "beta"), false, null);

        Path file = Report.write(vault, Fixtures.clock());
        String html = Files.readString(file);

        assertEquals(vault.root.resolve("report.html"), file, "report must be written into the vault root");
        assertTrue(html.contains("id=\"alpha\"") && html.contains("id=\"beta\""), "every project must get a section");
        assertTrue(html.contains("context.md") && html.contains("notes.md"), "every document must be listed");
        assertTrue(html.contains("<pre>b</pre>"), "document text must be in the report");
    }

    @Test
    void heatmapHasOneCellPerDayFromMondayTwelveWeeksAgo()
    {
        LocalDate today = LocalDate.of(2026, 10, 8);

        String heat = Report.heatmap(List.of(new Report.Change(today, "p/a.md")), change -> true, today);

        int cells = heat.split("<i ", -1).length - 1;
        assertEquals(88, cells, "12 weeks back to Monday 2026-07-13 through Thursday 2026-10-08 is 88 days");
        assertTrue(heat.contains("title=\"2026-10-08: 1\""), "today's change must be counted");
    }

    @Test
    void sectionsSplitAtHeadingsOutsideCodeFences()
    {
        String body = "intro\n## Open\n- [ ] first\n```\n## not a heading\n```\n### Detail\nde\n";

        assertEquals(List.of(new Report.Section(0, "", "intro\n"),
                             new Report.Section(2, "Open", "- [ ] first\n```\n## not a heading\n```\n"),
                             new Report.Section(3, "Detail", "de\n")),
                     Report.sections(body), "text before the first heading must be kept and fenced headings must not split");
    }

    @Test
    void gistTakesTopLevelItemsAndTheirBoldLeads()
    {
        String text = "- [ ] **Lost payment** after relogin:\n      details\n  - nested\n* [x] plain `item`\n1. third\n";

        assertEquals(List.of("Lost payment", "plain `item`", "third"), Report.gist(text),
                     "one line per top-level item, nested items and continuation lines skipped");
        assertEquals(List.of("first line"), Report.gist("\nfirst line\nsecond\n"), "a section without items shows its first line");
        assertEquals(120, Report.gist("- " + "a".repeat(500)).get(0).length(), "a long item must be cut");
    }

    @Test
    void escapeNeutralizesMarkup()
    {
        assertEquals("&lt;b title=&quot;x&quot;&gt;&amp;", Report.esc("<b title=\"x\">&"), "user text must never become markup");
    }
}
