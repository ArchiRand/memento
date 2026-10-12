package io.github.archirand.memento;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class VaultTest
{
    @TempDir
    Path tmp;

    @Test
    void initCreatesRepositoryWithCommittedGitignore() throws Exception
    {
        Vault vault = new Vault(tmp.resolve("vault"));

        vault.init();

        assertEquals(Vault.GITIGNORE, Files.readString(vault.root.resolve(".gitignore")), ".gitignore must hide lock, tmp, report and obsidian files");
        assertEquals("init memento vault", Fixtures.log(vault), "a fresh vault must start with exactly one init commit");
    }

    @Test
    void secondInitDoesNotCommitAgain() throws Exception
    {
        Vault vault = new Vault(tmp.resolve("vault"));

        vault.init();
        vault.init();

        assertEquals("init memento vault", Fixtures.log(vault), "init must be idempotent");
    }

    @Test
    void manualEditsGetTheirOwnCommitBeforeToolCommit() throws Exception
    {
        Vault vault = new Vault(tmp.resolve("vault"));
        vault.init();
        Files.createDirectories(vault.root.resolve("p"));
        Files.writeString(vault.root.resolve("p/hand.md"), "edited by hand");
        Path tool = vault.root.resolve("p/tool.md");

        String warning = vault.locked(() -> {
            vault.commitManualChanges();
            Files.writeString(tool, "written by tool");
            return vault.commit("write p/tool.md", List.of(tool));
        });

        assertNull(warning, "commit in a healthy vault must not warn");
        assertEquals("write p/tool.md\nmanual changes\ninit memento vault", Fixtures.log(vault),
                     "manual edits must be committed separately and before the tool's own commit");
    }

    @Test
    void userHooksDoNotBlockCommits() throws Exception
    {
        Vault vault = new Vault(tmp.resolve("vault"));
        vault.init();
        Path hooks = Files.createDirectories(tmp.resolve("hooks"));
        for (String hook : new String[] {"pre-commit", "prepare-commit-msg", "commit-msg"})
        {
            Files.writeString(hooks.resolve(hook), "#!/bin/sh\nexit 1\n");
            hooks.resolve(hook).toFile().setExecutable(true);
        }
        Git.run(vault.root, "config", "core.hooksPath", hooks.toString());
        Path doc = vault.root.resolve("p/a.md");
        Files.createDirectories(doc.getParent());

        String warning = vault.locked(() -> {
            Files.writeString(doc, "x");
            return vault.commit("write p/a.md", List.of(doc));
        });

        assertNull(warning, "a user's hooks (core.hooksPath, prepare-commit-msg included) must not block vault commits");
        assertEquals("write p/a.md", Fixtures.log(vault).lines().findFirst().orElseThrow(), "the commit must land despite failing hooks");
    }

    @Test
    void commitOfUnchangedFileIsSilentNoop() throws Exception
    {
        Vault vault = new Vault(tmp.resolve("vault"));
        vault.init();
        Path doc = vault.root.resolve("p/a.md");
        Files.createDirectories(doc.getParent());
        vault.locked(() -> {
            Files.writeString(doc, "same");
            return vault.commit("write p/a.md", List.of(doc));
        });

        String warning = vault.locked(() -> {
            Files.writeString(doc, "same");
            return vault.commit("write p/a.md", List.of(doc));
        });

        assertNull(warning, "rewriting identical content must not report a git failure");
        assertEquals("write p/a.md\ninit memento vault", Fixtures.log(vault), "nothing to commit means no commit");
    }
}
