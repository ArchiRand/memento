package io.github.archirand.memento;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallLogTest
{
    @TempDir
    Path tmp;

    @Test
    void eachEventIsOneJsonLineInFileOfTheDay() throws Exception
    {
        CallLog log = new CallLog(new Vault(tmp), Fixtures.clock(), "server");

        log.write(Map.of("tool", "read_context"));
        log.write(Map.of("tool", "write_document"));

        List<String> lines = Files.readAllLines(tmp.resolve(".logs/2026-10-08.jsonl"));
        JsonNode first = new ObjectMapper().readTree(lines.get(0));
        assertEquals(2, lines.size(), "every event must be exactly one line");
        assertEquals("server", first.get("mode").asText(), "mode tells server calls from prime runs");
        assertEquals("read_context", first.get("tool").asText(), "given fields must be kept");
        assertTrue(first.has("ts") && first.has("pid"), "time and process are needed to tell sessions apart");
    }

    @Test
    void longStringsAreCutWithTheirLength()
    {
        String content = "x".repeat(CallLog.MAX_STRING + 1);

        Object shortened = CallLog.shorten(Map.of("edits", List.of(Map.of("oldText", content))));

        String cut = "x".repeat(CallLog.MAX_STRING) + "…(301 chars)";
        assertEquals(Map.of("edits", List.of(Map.of("oldText", cut))), shortened, "nested long strings must be cut, not copied whole");
    }

    @Test
    void pruneDeletesOnlyFilesOlderThanKeepDays() throws Exception
    {
        Path dir = Files.createDirectories(tmp.resolve(CallLog.DIR));
        for (String day : new String[] {"2026-09-23", "2026-09-24", "2026-10-08"})
        {
            Files.writeString(dir.resolve(day + ".jsonl"), "{}\n");
        }

        new CallLog(new Vault(tmp), Fixtures.clock(), "server").prune();

        assertFalse(Files.exists(dir.resolve("2026-09-23.jsonl")), "a file older than 14 days must be deleted");
        assertTrue(Files.exists(dir.resolve("2026-09-24.jsonl")), "a file exactly 14 days old must stay");
        assertTrue(Files.exists(dir.resolve("2026-10-08.jsonl")), "today's file must stay");
    }

    @Test
    void unwritableLogDirectoryDoesNotBreakTheCall() throws Exception
    {
        Path notADirectory = Files.writeString(tmp.resolve("vault"), "a file where the vault should be");

        assertDoesNotThrow(() -> new CallLog(new Vault(notADirectory), Fixtures.clock(), "server").write(Map.of("tool", "x")),
                           "journal failures must never reach the tool call");
    }
}
