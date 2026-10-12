package io.github.archirand.memento;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Storage root: a git repository with one directory per project.
 * Every mutation runs inside {@link #locked} and ends with {@link #commit}.
 */
final class Vault
{
    static final String GITIGNORE = ".lock\n*.tmp\nreport.html\n.obsidian/\n.logs/\n.DS_Store\n";

    /** FileLock is per process: threads of one JVM are serialized here. */
    private static final Object JVM_LOCK = new Object();

    final Path root;

    Vault(Path root)
    {
        this.root = root;
    }

    /**
     * {@code $MEMENTO_HOME} (a leading {@code ~} is expanded) or {@code ~/.memento}.
     */
    static Vault fromEnv()
    {
        String home = System.getProperty("user.home");
        String env = System.getenv("MEMENTO_HOME");
        if (env == null || env.isBlank())
        {
            return new Vault(Path.of(home, ".memento"));
        }
        return new Vault(Path.of(env.startsWith("~") ? home + env.substring(1) : env));
    }

    /**
     * Creates the directory and the git repository with .gitignore if they are missing. Idempotent.
     */
    void init() throws IOException
    {
        Files.createDirectories(root);
        if (Files.isDirectory(root.resolve(".git")))
        {
            return;
        }
        Git.run(root, "init", "-q");
        Files.writeString(root.resolve(".gitignore"), GITIGNORE);
        Git.run(root, "add", ".gitignore");
        gitCommit("init memento vault");
    }

    /**
     * Runs the action under the inter-process lock on {@code .lock}; initializes the vault under the same lock first.
     */
    <T> T locked(IoAction<T> action) throws IOException
    {
        synchronized (JVM_LOCK)
        {
            Files.createDirectories(root);
            try (FileChannel channel = FileChannel.open(root.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock())
            {
                init();
                return action.run();
            }
        }
    }

    /**
     * Commits everything uncommitted (edits made by hand) as a separate commit. Call inside {@link #locked}.
     * A git failure is only reported to stderr: the changes stay in the work tree and go into the next commit.
     */
    void commitManualChanges()
    {
        try
        {
            if (!Git.run(root, "status", "--porcelain", "--untracked-files=all").isEmpty())
            {
                Git.run(root, "add", "-A");
                gitCommit("manual changes");
            }
        }
        catch (IOException e)
        {
            System.err.println("memento: committing manual changes failed: " + e.getMessage());
        }
    }

    /**
     * Stages the paths (deletions included) and commits them. Call inside {@link #locked}.
     *
     * @return null on success, otherwise a warning: the files are saved and get committed with the next write
     */
    String commit(String message, List<Path> paths)
    {
        try
        {
            List<String> args = new ArrayList<>(List.of("add", "-A", "--"));
            // git pathspecs use '/' on every OS
            paths.forEach(path -> args.add(root.relativize(path).toString().replace(File.separatorChar, '/')));
            Git.run(root, args.toArray(String[]::new));
            if (Git.tryRun(root, "diff", "--cached", "--quiet").isEmpty())
            {
                gitCommit(message);
            }
            return null;
        }
        catch (IOException e)
        {
            return "git commit failed (" + e.getMessage() + "); the file is saved and will be committed with the next write";
        }
    }

    /**
     * Commits with a fixed author, no signing and no hooks: --no-verify alone still runs prepare-commit-msg and post-commit.
     */
    private void gitCommit(String message) throws IOException
    {
        String noHooks = "core.hooksPath=" + root.resolve(".git").resolve("memento-no-hooks").toAbsolutePath();
        Git.run(root, "-c", "user.name=memento", "-c", "user.email=memento@localhost", "-c", "commit.gpgsign=false",
                "-c", noHooks, "commit", "-q", "--no-verify", "-m", message);
    }

    @FunctionalInterface
    interface IoAction<T>
    {
        T run() throws IOException;
    }
}
