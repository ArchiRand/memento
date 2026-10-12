package io.github.archirand.memento;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrimeTest
{
    private static final Project IN_GIT = new Project("p", Path.of("/x"), null, true, true);
    private static final Project OUTSIDE_GIT = new Project("p", Path.of("/x"), null, true, false);

    @TempDir
    Path tmp;

    @Test
    void coreDocumentsComeInContextThenActiveTaskOrder()
    {
        String context = Prime.render(IN_GIT, List.of(core("activeTask.md", "tasks"), core("aaa.md", "misc"), core("context.md", "ctx")), Fixtures.NOW);

        assertTrue(context.indexOf("## context.md") < context.indexOf("## activeTask.md"), "context.md must come first");
        assertTrue(context.indexOf("## activeTask.md") < context.indexOf("## aaa.md"), "activeTask.md must come before other core documents");
    }

    @Test
    void coreDocumentOverBudgetIsListedNotTruncated()
    {
        String huge = "x".repeat(Hygiene.BUDGET);

        String context = Prime.render(IN_GIT, List.of(core("context.md", "ctx"), core("activeTask.md", huge)), Fixtures.NOW);

        assertFalse(context.contains(huge.substring(0, 100)), "an over-budget document must not be partially included");
        assertTrue(context.contains("## Not loaded (over budget) — call read_documents for these now, before answering\n- activeTask.md"), "an over-budget document must be named for reading");
        assertTrue(context.length() <= Hygiene.BUDGET, "prime output must fit the hook budget");
    }

    @Test
    void emptyProjectGetsHintOnlyInsideGit()
    {
        assertTrue(Prime.render(IN_GIT, List.of(), Fixtures.NOW).startsWith("No memento memory for `p`"), "a git project without memory gets a hint");
        assertEquals("", Prime.render(OUTSIDE_GIT, List.of(), Fixtures.NOW), "a random folder without memory must stay silent");
    }

    @Test
    void staleActiveTaskProducesWarning()
    {
        Doc stale = new Doc("activeTask.md", List.of("a", "b"), true, "2026-09-01T00:00:00Z", "tasks");

        assertTrue(Prime.render(IN_GIT, List.of(stale), Fixtures.NOW).contains("activeTask.md not updated for 37 days"), "stale activeTask must be flagged");
    }

    @Test
    void malformedInputPrintsNothing()
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        Prime.run(new ByteArrayInputStream("not json".getBytes(StandardCharsets.UTF_8)), new PrintStream(out), new Vault(tmp), Fixtures.clock());

        assertEquals("", out.toString(StandardCharsets.UTF_8), "a broken hook input must never break session start");
    }

    private static Doc core(String name, String body)
    {
        return new Doc(name, List.of("a", "b"), true, "2026-10-08T00:00:00Z", body);
    }

    @Test
    void coreWithinBudgetLoadsFullyAndOneCharMoreWarns()
    {
        int overhead = Hygiene.coreChars(List.of(core("context.md", ""), core("activeTask.md", "")));
        List<Doc> fits = List.of(core("context.md", "c".repeat(1000)), core("activeTask.md", "a".repeat(Hygiene.CORE_BUDGET - overhead - 1000)));
        List<Doc> over = List.of(core("context.md", "c".repeat(1001)), core("activeTask.md", "a".repeat(Hygiene.CORE_BUDGET - overhead - 1000)));

        assertEquals(List.of(), Hygiene.warnings(fits, Fixtures.NOW), "core exactly at the budget must not warn");
        assertFalse(Prime.render(IN_GIT, fits, Fixtures.NOW).contains("Not loaded"), "core that does not warn must load completely");
        assertTrue(Hygiene.warnings(over, Fixtures.NOW).get(0).startsWith("core documents take " + (Hygiene.CORE_BUDGET + 1)),
                   "core that does not fit prime must warn");
    }
}
