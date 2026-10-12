package io.github.archirand.memento;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Working directory → project key: registry by remote, registry by root, origin name, root folder name.
 */
final class ProjectResolver
{
    static final Pattern KEY = Pattern.compile("[a-z0-9][a-z0-9._-]*");

    private ProjectResolver()
    {
    }

    static Project resolve(Path cwd, Registry registry)
    {
        Optional<String> top = Git.tryRun(cwd, "rev-parse", "--show-toplevel");
        Path root = realPath(top.map(Path::of).orElse(cwd));
        String remote = top.flatMap(t -> Git.tryRun(root, "remote", "get-url", "origin"))
                           .filter(url -> !url.isBlank())
                           .map(ProjectResolver::normalizeRemote)
                           .orElse(null);
        String key = Optional.ofNullable(remote)
                             .flatMap(registry::findByRemote)
                             .or(() -> registry.findByPath(root.toString()))
                             .orElseGet(() -> keyFrom(remote != null
                                                      ? remote.substring(remote.lastIndexOf('/') + 1)
                                                      : root.getFileName() == null ? "root" : root.getFileName().toString()));
        return new Project(key, root, remote, true, top.isPresent());
    }

    static Project explicit(String key)
    {
        if (!KEY.matcher(key).matches())
        {
            throw new MementoException("invalid project key '" + key + "': use lowercase letters, digits, '.', '_', '-', starting with a letter or digit");
        }
        return new Project(key, null, null, false, false);
    }

    /**
     * {@code git@host:a/b.git}, {@code https://user@host:443/a/b} and the like → {@code host/a/b}.
     */
    static String normalizeRemote(String url)
    {
        String s = url.strip().toLowerCase(Locale.ROOT)
                      .replaceFirst("^[a-z][a-z0-9+.-]*://", "")
                      .replaceFirst("^[^@/]+@", "")
                      .replaceFirst("^([^/:]+):\\d+/", "$1/")
                      .replaceFirst("^([^/:]+):", "$1/");
        return s.replaceFirst("/+$", "").replaceFirst("\\.git$", "");
    }

    static String keyFrom(String name)
    {
        String key = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "-").replaceAll("^[^a-z0-9]+", "");
        return key.isEmpty() ? "default" : key;
    }

    private static Path realPath(Path path)
    {
        try
        {
            return path.toRealPath();
        }
        catch (IOException e)
        {
            return path.toAbsolutePath().normalize();
        }
    }
}
