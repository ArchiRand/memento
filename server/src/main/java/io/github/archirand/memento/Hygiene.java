package io.github.archirand.memento;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Core budget and staleness checks shared by prime, read_context and the mutating tools.
 */
final class Hygiene
{
    /** Claude Code caps hook additionalContext at 10 000 chars; keep a margin. */
    static final int BUDGET = 9_500;
    /** Room for core blocks in prime; the rest of BUDGET is for the header, warnings and document lists. */
    static final int CORE_BUDGET = 9_000;
    static final int STALE_DAYS = 7;

    private Hygiene()
    {
    }

    static int coreChars(List<Doc> docs)
    {
        return docs.stream().filter(Doc::core).mapToInt(doc -> doc.contextBlock().length()).sum();
    }

    static List<String> warnings(List<Doc> docs, Instant now)
    {
        List<String> warnings = new ArrayList<>();
        int core = coreChars(docs);
        if (core > CORE_BUDGET)
        {
            warnings.add("core documents take " + core + " chars, budget is " + CORE_BUDGET
                         + ": the hook lists the rest under Not loaded and they are read with one more call; do not trim documents just to fit");
        }
        docs.stream()
            .filter(doc -> doc.name().equals("activeTask.md"))
            .map(Doc::modifiedAt)
            .filter(at -> at != null && Duration.between(at, now).toDays() > STALE_DAYS)
            .findFirst()
            .ifPresent(at -> warnings.add("activeTask.md not updated for " + Duration.between(at, now).toDays() + " days"));
        return warnings;
    }

    static String age(Doc doc, Instant now)
    {
        Instant at = doc.modifiedAt();
        if (at == null)
        {
            return "update time unknown";
        }
        long days = Duration.between(at, now).toDays();
        return days == 0 ? "updated today" : "updated " + days + " days ago";
    }
}
