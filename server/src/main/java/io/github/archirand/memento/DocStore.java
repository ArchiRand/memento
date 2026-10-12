package io.github.archirand.memento;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Document operations over a {@link Vault}. Every mutation is one commit.
 */
final class DocStore
{
    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{4,40}");

    private final Vault vault;
    private final Clock clock;

    DocStore(Vault vault, Clock clock)
    {
        this.vault = vault;
        this.clock = clock;
    }

    /**
     * Keys of all projects that have a directory in the vault.
     */
    List<String> projects() throws IOException
    {
        if (!Files.isDirectory(vault.root))
        {
            return List.of();
        }
        try (Stream<Path> dirs = Files.list(vault.root))
        {
            return dirs.filter(Files::isDirectory)
                       .map(dir -> dir.getFileName().toString())
                       .filter(name -> ProjectResolver.KEY.matcher(name).matches())
                       .sorted()
                       .toList();
        }
    }

    /**
     * All documents of the project sorted by name; empty if the project has none.
     */
    List<Doc> list(String key) throws IOException
    {
        Path dir = vault.root.resolve(key);
        if (!Files.isDirectory(dir))
        {
            return List.of();
        }
        List<Doc> docs = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir))
        {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList())
            {
                String name = file.getFileName().toString();
                if (Doc.NAME.matcher(name).matches())
                {
                    docs.add(Doc.parse(name, readText(file)));
                }
            }
        }
        return docs;
    }

    Optional<Doc> read(String key, String name) throws IOException
    {
        checkName(name);
        Path file = file(key, name);
        return Files.isRegularFile(file) && storedAs(file, name) ? Optional.of(Doc.parse(name, readText(file))) : Optional.empty();
    }

    /**
     * @param core null keeps the flag of an existing document (false for a new one)
     */
    Saved write(Project project, String name, String content, List<String> tags, Boolean core, String expectedRevision)
            throws IOException
    {
        checkTags(tags);
        return mutate(project, name, "write", current -> {
            checkRevision(current, expectedRevision);
            return new Doc(name, tags, core != null ? core : current.map(Doc::core).orElse(false), now(), content);
        });
    }

    Saved edit(Project project, String name, List<Edit> edits, String expectedRevision) throws IOException
    {
        if (edits == null || edits.isEmpty())
        {
            throw new MementoException("edits must not be empty");
        }
        return mutate(project, name, "edit", current -> {
            Doc doc = current.orElseThrow(() -> new MementoException("document " + name + " not found"));
            checkRevision(current, expectedRevision);
            String body = doc.body();
            for (int i = 0; i < edits.size(); i++)
            {
                body = applyEdit(body, edits.get(i), i + 1);
            }
            return new Doc(name, doc.tags(), doc.core(), now(), body);
        });
    }

    Saved append(Project project, String name, String content, List<String> tags, Boolean core) throws IOException
    {
        return mutate(project, name, "append", current -> {
            if (current.isPresent())
            {
                Doc doc = current.get();
                String body = doc.body().isBlank() ? content : doc.body().stripTrailing() + "\n\n" + content;
                return new Doc(name, doc.tags(), doc.core(), now(), body);
            }
            checkTags(tags);
            return new Doc(name, tags, Boolean.TRUE.equals(core), now(), content);
        });
    }

    /**
     * Deletes the document with a commit.
     *
     * @return git warning or null
     */
    String delete(Project project, String name) throws IOException
    {
        checkName(name);
        Path file = file(project.key(), name);
        return vault.locked(() -> {
            vault.commitManualChanges();
            checkCase(file, name);
            if (!Files.isRegularFile(file))
            {
                throw new MementoException("document " + name + " not found");
            }
            Files.delete(file);
            return vault.commit("delete " + project.key() + "/" + name, List.of(file));
        });
    }

    /**
     * Commits touching the document, newest first; works for deleted documents too.
     */
    List<HistoryEntry> history(String key, String name, int limit) throws IOException
    {
        checkName(name);
        String log = Git.tryRun(vault.root, "log", "-n", String.valueOf(limit), "--date=iso-strict",
                                "--format=%h%x09%ad%x09%s", "--", key + "/" + name).orElse("");
        return log.lines()
                  .filter(line -> !line.isBlank())
                  .map(line -> line.split("\t", 3))
                  .map(parts -> new HistoryEntry(parts[0], parts[1], parts[2]))
                  .toList();
    }

    /**
     * The document file as it was in the given commit.
     */
    String contentAt(String key, String name, String commit) throws IOException
    {
        checkName(name);
        if (!COMMIT.matcher(commit).matches())
        {
            throw new MementoException("invalid commit '" + commit + "': expected a hex sha from history");
        }
        try
        {
            return Git.runRaw(vault.root, "show", commit + ":" + key + "/" + name);
        }
        catch (IOException e)
        {
            throw new MementoException("no version of " + name + " in commit " + commit);
        }
    }

    static String applyEdit(String body, Edit edit, int number)
    {
        String oldText = edit.oldText();
        if (oldText == null || oldText.isEmpty())
        {
            throw new MementoException("edit #" + number + ": oldText must not be empty");
        }
        int first = body.indexOf(oldText);
        if (first < 0)
        {
            throw new MementoException("edit #" + number + ": oldText not found");
        }
        int matches = 0;
        for (int at = first; at >= 0; at = body.indexOf(oldText, at + 1))
        {
            matches++;
        }
        if (matches > 1)
        {
            throw new MementoException("edit #" + number + ": oldText matches " + matches + " times, include more surrounding text");
        }
        String newText = edit.newText() == null ? "" : edit.newText();
        return body.substring(0, first) + newText + body.substring(first + oldText.length());
    }

    private Saved mutate(Project project, String name, String operation, Function<Optional<Doc>, Doc> change) throws IOException
    {
        checkName(name);
        Path file = file(project.key(), name);
        return vault.locked(() -> {
            vault.commitManualChanges();
            checkCase(file, name);
            if (!operation.equals("write") && Files.isRegularFile(file))
            {
                String error = Doc.frontmatterError(readText(file));
                if (error != null)
                {
                    throw new MementoException("frontmatter of " + name + " is not valid YAML (" + error
                                               + "); fix it by hand or rewrite the document with write_document");
                }
            }
            Doc next = change.apply(read(project.key(), name));
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(name + ".tmp");
            Files.writeString(tmp, next.render());
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            List<Path> paths = new ArrayList<>(List.of(file));
            if (project.fromCwd())
            {
                Registry registry = Registry.load(vault.root);
                if (registry.register(project.key(), project.root().toString(), project.remote()))
                {
                    registry.save();
                    paths.add(registry.file);
                }
            }
            return new Saved(next, vault.commit(operation + " " + project.key() + "/" + name, paths));
        });
    }

    private static void checkRevision(Optional<Doc> current, String expected)
    {
        if (expected == null || expected.isBlank())
        {
            return;
        }
        String actual = current.map(Doc::revision).orElse(null);
        if (actual == null)
        {
            throw new MementoException("revision mismatch: the document does not exist");
        }
        if (!expected.equals(actual))
        {
            throw new MementoException("revision mismatch: current revision is " + actual + "; re-read the document, merge your changes and retry");
        }
    }

    private static void checkTags(List<String> tags)
    {
        if (tags == null || tags.size() < 2)
        {
            throw new MementoException("at least 2 tags are required");
        }
        for (String tag : tags)
        {
            if (tag == null || !Doc.TAG.matcher(tag).matches())
            {
                throw new MementoException("invalid tag '" + tag + "': lowercase English words, digits, '-', '.', '_'");
            }
        }
    }

    private static void checkName(String name)
    {
        if (name == null || !Doc.NAME.matcher(name).matches())
        {
            throw new MementoException("invalid document name '" + name + "': expected something like activeTask.md");
        }
    }

    /**
     * On a case-insensitive file system Foo.md opens foo.md: refuse instead of overwriting another document.
     */
    private static void checkCase(Path file, String name) throws IOException
    {
        if (Files.exists(file) && !storedAs(file, name))
        {
            try (Stream<Path> files = Files.list(file.getParent()))
            {
                String actual = files.map(f -> f.getFileName().toString()).filter(name::equalsIgnoreCase).findFirst().orElse(name);
                throw new MementoException("document " + actual + " already exists and differs from " + name + " only in case; use " + actual);
            }
        }
    }

    /**
     * The directory holds the file under exactly this name, not just one that differs in case.
     */
    private static boolean storedAs(Path file, String name) throws IOException
    {
        try (Stream<Path> files = Files.list(file.getParent()))
        {
            return files.anyMatch(f -> f.getFileName().toString().equals(name));
        }
    }

    /**
     * Malformed UTF-8 (a file saved by hand in another encoding) becomes U+FFFD instead of failing the whole project.
     */
    private static String readText(Path file) throws IOException
    {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private Path file(String key, String name)
    {
        return vault.root.resolve(key).resolve(name);
    }

    private String now()
    {
        return Doc.stamp(clock.instant());
    }

    record Edit(String oldText, String newText)
    {
    }

    record HistoryEntry(String commit, String date, String message)
    {
    }

    /**
     * @param gitWarning null if the commit succeeded
     */
    record Saved(Doc doc, String gitWarning)
    {
    }
}
