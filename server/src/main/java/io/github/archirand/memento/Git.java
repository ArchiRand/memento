package io.github.archirand.memento;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Thin wrapper over the git CLI.
 */
final class Git
{
    private Git()
    {
    }

    /**
     * Runs {@code git -C dir args...} and returns stdout untouched.
     *
     * @throws IOException if git can't be started or exits with a non-zero code
     */
    static String runRaw(Path dir, String... args) throws IOException
    {
        List<String> command = new ArrayList<>(List.of("git", "-C", dir.toString()));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command);
        // GIT_DIR, GIT_INDEX_FILE etc. inherited from a git hook would redirect commands away from the vault
        builder.environment().keySet().removeIf(name -> name.startsWith("GIT_"));
        Process process = builder.start();
        process.getOutputStream().close();
        CompletableFuture<byte[]> stderr = CompletableFuture.supplyAsync(() -> readQuietly(process.getErrorStream()));
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try
        {
            if (process.waitFor() != 0)
            {
                String error = new String(stderr.join(), StandardCharsets.UTF_8).strip();
                throw new IOException("git " + String.join(" ", args) + " failed: " + error);
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while running git", e);
        }
        return stdout;
    }

    /**
     * Same as {@link #runRaw} with surrounding whitespace stripped.
     */
    static String run(Path dir, String... args) throws IOException
    {
        return runRaw(dir, args).strip();
    }

    /**
     * Same as {@link #run}, empty on any failure.
     */
    static Optional<String> tryRun(Path dir, String... args)
    {
        try
        {
            return Optional.of(run(dir, args));
        }
        catch (IOException e)
        {
            return Optional.empty();
        }
    }

    private static byte[] readQuietly(InputStream in)
    {
        try (in)
        {
            return in.readAllBytes();
        }
        catch (IOException e)
        {
            return new byte[0];
        }
    }
}
