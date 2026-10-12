package io.github.archirand.memento;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Shared helpers for tests: fixed clock, git repositories, vault log.
 */
final class Fixtures
{
    static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");

    private Fixtures()
    {
    }

    static Clock clock()
    {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    /**
     * Creates a git repository in dir with an optional origin; returns its real path (macOS: /var → /private/var).
     */
    static Path gitRepo(Path dir, String remote) throws IOException
    {
        Files.createDirectories(dir);
        Git.run(dir, "init", "-q");
        if (remote != null)
        {
            Git.run(dir, "remote", "add", "origin", remote);
        }
        return dir.toRealPath();
    }

    /**
     * Commit subjects of the vault, newest first.
     */
    static String log(Vault vault) throws IOException
    {
        return Git.run(vault.root, "log", "--format=%s");
    }
}
