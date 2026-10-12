package io.github.archirand.memento;

import java.nio.file.Path;

/**
 * A resolved project.
 *
 * @param key     storage directory name
 * @param root    project root (git top level or cwd); null for an explicit key
 * @param remote  normalized origin url, null if none
 * @param fromCwd resolved from the working directory, so writes register root and remote
 * @param inGit   the working directory is inside a git repository
 */
record Project(String key, Path root, String remote, boolean fromCwd, boolean inGit)
{
}
