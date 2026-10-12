package io.github.archirand.memento;

import io.github.archirand.memento.DocStore.Edit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocStoreTest
{
    private static final List<String> TAGS = List.of("task", "status");

    @TempDir
    Path tmp;

    private Vault vault;
    private DocStore store;
    private Project project;

    @BeforeEach
    void setUp()
    {
        vault = new Vault(tmp.resolve("vault"));
        store = new DocStore(vault, Fixtures.clock());
        project = ProjectResolver.explicit("p");
    }

    @Test
    void writtenDocumentReadsBackWithSameRevision() throws Exception
    {
        DocStore.Saved saved = store.write(project, "a.md", "hello\n", TAGS, true, null);

        Doc read = store.read("p", "a.md").orElseThrow();

        assertEquals("hello\n", read.body(), "body must be stored as given");
        assertEquals(saved.doc().revision(), read.revision(), "revision returned by write must match what read sees");
        assertEquals("2026-10-08T12:00:00Z", read.lastModified(), "write must stamp lastModified from the clock");
        assertEquals("write p/a.md", Fixtures.log(vault).lines().findFirst().orElseThrow(), "every write is a commit");
    }

    @Test
    void writeWithStaleRevisionIsRejectedAndKeepsDocument() throws Exception
    {
        String first = store.write(project, "a.md", "v1", TAGS, false, null).doc().revision();
        store.write(project, "a.md", "v2", TAGS, false, first);

        assertThrows(MementoException.class, () -> store.write(project, "a.md", "v3", TAGS, false, first),
                     "a write based on an old revision must not overwrite a newer one");
        assertEquals("v2", store.read("p", "a.md").orElseThrow().body(), "rejected write must leave the document untouched");
    }

    @Test
    void editReplacesUniqueTextsInOneCommit() throws Exception
    {
        store.write(project, "a.md", "## Next\n- one\n## Done\n- zero\n", TAGS, true, null);

        store.edit(project, "a.md", List.of(new Edit("- one\n", ""), new Edit("- zero\n", "- zero\n- one\n")), null);

        assertEquals("## Next\n## Done\n- zero\n- one\n", store.read("p", "a.md").orElseThrow().body(), "edits apply in order");
        assertEquals("edit p/a.md\nwrite p/a.md\ninit memento vault", Fixtures.log(vault), "a batch of edits is a single commit");
    }

    @Test
    void editBatchWithOneBadTextChangesNothing() throws Exception
    {
        store.write(project, "a.md", "x y x", TAGS, false, null);

        MementoException missing = assertThrows(MementoException.class,
                () -> store.edit(project, "a.md", List.of(new Edit("y", "Y"), new Edit("nope", "")), null));
        MementoException ambiguous = assertThrows(MementoException.class,
                () -> store.edit(project, "a.md", List.of(new Edit("x", "X")), null));

        assertTrue(missing.getMessage().startsWith("edit #2: oldText not found"), "error must name the failing edit");
        assertTrue(ambiguous.getMessage().contains("matches 2 times"), "error must report ambiguity");
        assertEquals("x y x", store.read("p", "a.md").orElseThrow().body(), "a failed batch must not apply its valid edits");
    }
    @Test
    void appendAddsBlankLineSeparatorAndKeepsMetadata() throws Exception
    {
        store.write(project, "log.md", "first\n", TAGS, false, null);

        store.append(project, "log.md", "second\n", null, null);

        Doc doc = store.read("p", "log.md").orElseThrow();
        assertEquals("first\n\nsecond\n", doc.body(), "appended text must be separated by one blank line");
        assertEquals(TAGS, doc.tags(), "append must not touch tags of an existing document");
    }

    @Test
    void appendToMissingDocumentNeedsTags() throws Exception
    {
        assertThrows(MementoException.class, () -> store.append(project, "log.md", "x", null, null),
                     "a document created by append must get tags like any other");

        store.append(project, "log.md", "x", TAGS, null);

        assertEquals("x", store.read("p", "log.md").orElseThrow().body(), "append with tags must create the document");
    }

    @Test
    void deletedDocumentCanBeRestoredFromHistory() throws Exception
    {
        store.write(project, "a.md", "keep me", TAGS, false, null);
        store.delete(project, "a.md");

        List<DocStore.HistoryEntry> history = store.history("p", "a.md", 10);
        String restored = store.contentAt("p", "a.md", history.get(1).commit());

        assertTrue(store.read("p", "a.md").isEmpty(), "deleted document must be gone");
        assertEquals("delete p/a.md", history.get(0).message(), "history must include the deletion");
        assertEquals("keep me", Doc.parse("a.md", restored).body(), "the version before deletion must be retrievable");
    }

    @Test
    void documentNameWithPathTraversalIsRejected()
    {
        for (String bad : new String[] {"../x.md", "a/b.md", ".hidden.md", "notes.txt"})
        {
            assertThrows(MementoException.class, () -> store.write(project, bad, "x", TAGS, false, null),
                         "name '" + bad + "' must be rejected before touching the file system");
        }
    }

    @Test
    void writeFromWorkingDirectoryRegistersRootAndRemote() throws Exception
    {
        Path repo = Fixtures.gitRepo(tmp.resolve("work"), "git@github.com:me/work.git");
        Project fromCwd = ProjectResolver.resolve(repo, Registry.load(vault.root));

        store.write(fromCwd, "context.md", "x", TAGS, true, null);

        Registry registry = Registry.load(vault.root);
        assertEquals("work", registry.findByPath(repo.toString()).orElseThrow(), "first write must register the project root");
        assertEquals("work", registry.findByRemote("github.com/me/work").orElseThrow(), "first write must register the remote");
        assertTrue(Files.exists(vault.root.resolve("work/context.md")), "document must land in the key's directory");
    }

    @Test
    void failedCommitKeepsWritesWorkingAndNextWriteCommitsEverything() throws Exception
    {
        vault.init();
        Path gitLock = Files.writeString(vault.root.resolve(".git/index.lock"), "");

        DocStore.Saved first = store.write(project, "a.md", "one", TAGS, false, null);
        DocStore.Saved second = store.write(project, "b.md", "two", TAGS, false, null);
        Files.delete(gitLock);
        DocStore.Saved third = store.write(project, "c.md", "three", TAGS, false, null);

        assertNotNull(first.gitWarning(), "a failed commit must be reported as a warning");
        assertNotNull(second.gitWarning(), "a persistent git failure must not turn writes into errors");
        assertEquals("two", store.read("p", "b.md").orElseThrow().body(), "the file must be saved even when git fails");
        assertNull(third.gitWarning(), "once git works again writes commit normally");
        assertEquals("write p/c.md\nmanual changes\ninit memento vault", Fixtures.log(vault),
                     "files saved while git was failing must be committed by the next write");
    }

    @Test
    void nameDifferingOnlyInCaseNeverOverwritesAnotherDocument() throws Exception
    {
        store.write(project, "architecture.md", "original", TAGS, false, null);

        try
        {
            store.write(project, "Architecture.md", "other", TAGS, false, null);
        }
        catch (MementoException e)
        {
            assertTrue(e.getMessage().contains("use architecture.md"), "the error must name the existing document");
        }

        assertEquals("original", store.read("p", "architecture.md").orElseThrow().body(),
                     "on a case-insensitive file system Architecture.md must not replace architecture.md");
    }

    @Test
    void writeWithoutCoreKeepsTheCurrentFlag() throws Exception
    {
        store.write(project, "activeTask.md", "v1", TAGS, true, null);

        store.write(project, "activeTask.md", "v2", TAGS, null, null);

        assertTrue(store.read("p", "activeTask.md").orElseThrow().core(), "rewriting without core must not drop the document out of prime");
    }

    @Test
    void editOfDocumentWithBrokenFrontmatterIsRejected() throws Exception
    {
        Path file = Files.createDirectories(vault.root.resolve("p")).resolve("a.md");
        String broken = "---\ncore: true\ntags: [task\n---\n\nbody\n";
        Files.writeString(file, broken);

        MementoException error = assertThrows(MementoException.class,
                () -> store.edit(project, "a.md", List.of(new Edit("body", "new")), null));

        assertTrue(error.getMessage().startsWith("frontmatter of a.md is not valid YAML"), "the error must say what to fix");
        assertEquals(broken, Files.readString(file), "a document whose metadata can't be read must not be rewritten with empty metadata");
    }

    @Test
    void fileInAnotherEncodingDoesNotBreakTheProject() throws Exception
    {
        store.write(project, "a.md", "fine", TAGS, false, null);
        Files.write(vault.root.resolve("p/latin1.md"), new byte[] {'c', (byte) 0xE9});

        List<Doc> docs = store.list("p");

        assertEquals(List.of("a.md", "latin1.md"), docs.stream().map(Doc::name).toList(), "one badly encoded file must not hide the others");
    }
}
