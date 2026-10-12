package io.github.archirand.memento;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectResolverTest
{
    @TempDir
    Path tmp;

    @Test
    void keyComesFromOriginRepositoryName() throws Exception
    {
        Path repo = Fixtures.gitRepo(tmp.resolve("klone_2"), "git@gitlab.example.com:klone-mobile/klone-mobile.git");

        Project project = ProjectResolver.resolve(repo, emptyRegistry());

        assertEquals("klone-mobile", project.key(), "clones in differently named folders must share the key from origin");
        assertTrue(project.inGit(), "a repository must be reported as git");
    }

    @Test
    void keyFallsBackToRepositoryRootNameFromSubdirectory() throws Exception
    {
        Path repo = Fixtures.gitRepo(tmp.resolve("My Proj"), null);
        Path sub = Files.createDirectories(repo.resolve("src/main"));

        Project project = ProjectResolver.resolve(sub, emptyRegistry());

        assertEquals("my-proj", project.key(), "without origin the key is the sanitized repository root name");
        assertEquals(repo, project.root(), "root must be the repository top level, not the subdirectory");
    }

    @Test
    void keyFallsBackToFolderNameOutsideGit() throws Exception
    {
        Path dir = Files.createDirectories(tmp.resolve("notes"));

        Project project = ProjectResolver.resolve(dir, emptyRegistry());

        assertEquals("notes", project.key(), "outside git the key is the folder name");
        assertFalse(project.inGit(), "a plain folder must not be reported as git");
    }

    @Test
    void sshAndHttpsRemotesNormalizeToSameValue()
    {
        String expected = "gitlab.example.com/group/repo";

        assertEquals(expected, ProjectResolver.normalizeRemote("git@gitlab.example.com:group/repo.git"), "scp-like ssh url");
        assertEquals(expected, ProjectResolver.normalizeRemote("https://gitlab.example.com/group/repo.git"), "https url");
        assertEquals(expected, ProjectResolver.normalizeRemote("ssh://git@gitlab.example.com:2222/group/repo"), "ssh url with port");
        assertEquals(expected, ProjectResolver.normalizeRemote("https://user@GitLab.example.com/group/repo/"), "user, case and trailing slash");
    }

    @Test
    void projectKeepsKeyAfterItGetsRemote() throws Exception
    {
        Path vaultRoot = Files.createDirectories(tmp.resolve("vault"));
        Path repo = Fixtures.gitRepo(tmp.resolve("foo"), null);
        Registry registry = Registry.load(vaultRoot);
        registry.register("foo", repo.toString(), null);
        Git.run(repo, "remote", "add", "origin", "git@github.com:me/foo-service.git");

        Project afterRemote = ProjectResolver.resolve(repo, registry);
        registry.register(afterRemote.key(), afterRemote.root().toString(), afterRemote.remote());
        Path otherClone = Fixtures.gitRepo(tmp.resolve("elsewhere"), "https://github.com/me/foo-service");

        assertEquals("foo", afterRemote.key(), "adding a remote must not orphan the memory registered by path");
        assertEquals("foo", ProjectResolver.resolve(otherClone, registry).key(), "a new clone must find the key through the registered remote");
    }

    @Test
    void explicitKeyRejectsTraversalAndUppercase()
    {
        for (String bad : new String[] {"..", ".", "../x", "a/b", "Klone"})
        {
            assertThrows(MementoException.class, () -> ProjectResolver.explicit(bad), "key '" + bad + "' must be rejected");
        }
    }

    private Registry emptyRegistry() throws Exception
    {
        return Registry.load(Files.createDirectories(tmp.resolve("vault")));
    }

    @Test
    void registryKeyOutsideTheKeyFormatIsRejected() throws Exception
    {
        Path vaultRoot = Files.createDirectories(tmp.resolve("vault"));
        Files.writeString(vaultRoot.resolve(Registry.FILE), "{\"../notes\": {\"paths\": [\"/x\"]}}");

        MementoException error = assertThrows(MementoException.class, () -> Registry.load(vaultRoot),
                                              "a hand-edited key must not lead outside the vault");

        assertTrue(error.getMessage().contains("invalid project key '../notes'"), "the error must name the bad key");
    }

    @Test
    void savedRegistryLeavesNoTemporaryFile() throws Exception
    {
        Path vaultRoot = Files.createDirectories(tmp.resolve("vault"));
        Registry registry = Registry.load(vaultRoot);
        registry.register("foo", "/x", null);

        registry.save();

        assertEquals("foo", Registry.load(vaultRoot).findByPath("/x").orElseThrow(), "saved registry must read back");
        assertFalse(Files.exists(vaultRoot.resolve(Registry.FILE + ".tmp")), "the temporary file must be moved into place");
    }
}
