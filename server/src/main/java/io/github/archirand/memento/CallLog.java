package io.github.archirand.memento;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Journal of tool calls and prime runs: one JSON line per event in {@code .logs/YYYY-MM-DD.jsonl} of the vault.
 * Never throws and never writes to stdout.
 */
final class CallLog
{
    static final String DIR = ".logs";
    static final int MAX_STRING = 300;
    static final int KEEP_DAYS = 14;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path dir;
    private final Clock clock;
    private final String mode;

    CallLog(Vault vault, Clock clock, String mode)
    {
        this.dir = vault.root.resolve(DIR);
        this.clock = clock;
        this.mode = mode;
    }

    /**
     * Appends one entry: ts, pid, mode, then the given fields in their order.
     */
    void write(Map<String, Object> fields)
    {
        try
        {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("ts", clock.instant().toString());
            entry.put("pid", ProcessHandle.current().pid());
            entry.put("mode", mode);
            entry.putAll(fields);
            Files.createDirectories(dir);
            Path file = dir.resolve(LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC) + ".jsonl");
            // ponytail: O_APPEND keeps short lines from several processes intact; no lock, journal is best effort
            Files.writeString(file, JSON.writeValueAsString(entry) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        catch (IOException | RuntimeException e)
        {
            System.err.println("memento log: " + e);
        }
    }

    /**
     * Deletes journal files older than {@link #KEEP_DAYS} days.
     */
    void prune()
    {
        String oldest = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).minusDays(KEEP_DAYS) + ".jsonl";
        if (!Files.isDirectory(dir))
        {
            return;
        }
        try (Stream<Path> files = Files.list(dir))
        {
            for (Path file : files.filter(f -> f.getFileName().toString().compareTo(oldest) < 0).toList())
            {
                Files.deleteIfExists(file);
            }
        }
        catch (IOException e)
        {
            System.err.println("memento log prune: " + e);
        }
    }

    /**
     * Copy of the value with strings longer than {@link #MAX_STRING} cut to "first chars…(N chars)"; walks maps and lists.
     */
    static Object shorten(Object value)
    {
        if (value instanceof String text)
        {
            return text.length() <= MAX_STRING ? text : text.substring(0, MAX_STRING) + "…(" + text.length() + " chars)";
        }
        if (value instanceof Map<?, ?> map)
        {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(key, shorten(item)));
            return copy;
        }
        if (value instanceof List<?> list)
        {
            return list.stream().map(CallLog::shorten).toList();
        }
        return value;
    }
}
