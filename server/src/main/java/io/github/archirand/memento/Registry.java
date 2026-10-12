package io.github.archirand.memento;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * projects.json: which project roots and git remotes belong to which key.
 * Filled automatically on writes, may be edited by hand.
 */
final class Registry
{
    static final String FILE = "projects.json";

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    final Path file;
    private final Map<String, Entry> entries;

    private Registry(Path file, Map<String, Entry> entries)
    {
        this.file = file;
        this.entries = entries;
    }

    static Registry load(Path vaultRoot) throws IOException
    {
        Path file = vaultRoot.resolve(FILE);
        if (!Files.exists(file))
        {
            return new Registry(file, new TreeMap<>());
        }
        TreeMap<String, Entry> entries;
        try
        {
            entries = JSON.readValue(file.toFile(), new TypeReference<TreeMap<String, Entry>>() {});
        }
        catch (IOException e)
        {
            throw new MementoException(file + " is not valid JSON, fix it by hand: " + e.getMessage());
        }
        for (String key : entries.keySet())
        {
            if (!ProjectResolver.KEY.matcher(key).matches())
            {
                throw new MementoException(file + ": invalid project key '" + key
                                           + "', fix it by hand: lowercase letters, digits, '.', '_', '-', starting with a letter or digit");
            }
        }
        return new Registry(file, entries);
    }

    Optional<String> findByRemote(String remote)
    {
        return find(entry -> entry.remotes().contains(remote));
    }

    Optional<String> findByPath(String path)
    {
        return find(entry -> entry.paths().contains(path));
    }

    /**
     * Adds the project root and the remote (both nullable) to the key's entry.
     *
     * @return true if the registry changed and must be saved
     */
    boolean register(String key, String path, String remote)
    {
        Entry entry = entries.get(key);
        List<String> paths = new ArrayList<>(entry == null ? List.of() : entry.paths());
        List<String> remotes = new ArrayList<>(entry == null ? List.of() : entry.remotes());
        boolean changed = entry == null;
        if (path != null && !paths.contains(path))
        {
            paths.add(path);
            changed = true;
        }
        if (remote != null && !remotes.contains(remote))
        {
            remotes.add(remote);
            changed = true;
        }
        entries.put(key, new Entry(paths, remotes));
        return changed;
    }

    /**
     * Writes through a temporary file and an atomic move: readers outside the vault lock never see a partial file.
     */
    void save() throws IOException
    {
        Path tmp = file.resolveSibling(FILE + ".tmp");
        JSON.writeValue(tmp.toFile(), entries);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private Optional<String> find(Predicate<Entry> predicate)
    {
        return entries.entrySet().stream()
                      .filter(e -> predicate.test(e.getValue()))
                      .map(Map.Entry::getKey)
                      .findFirst();
    }

    record Entry(List<String> paths, List<String> remotes)
    {
        Entry
        {
            paths = paths == null ? List.of() : paths;
            remotes = remotes == null ? List.of() : remotes;
        }
    }
}
