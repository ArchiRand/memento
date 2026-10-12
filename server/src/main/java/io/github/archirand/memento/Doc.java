package io.github.archirand.memento;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A memory document: YAML frontmatter (core, lastModified, tags) and a markdown body.
 * The file format is byte-compatible with mcp-memory-bank.
 */
record Doc(String name, List<String> tags, boolean core, String lastModified, String body)
{
    static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*\\.md");
    static final Pattern TAG = Pattern.compile("[a-z0-9][a-z0-9._-]*");
    /** context.md, then activeTask.md, then the rest by name. */
    static final Comparator<Doc> PRIME_ORDER = Comparator.comparingInt(Doc::primeRank).thenComparing(Doc::name);

    private static final YAMLMapper YAML = new YAMLMapper();
    private static final Pattern PLAIN_SCALAR = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._ -]*");

    /**
     * Parses the file text; CRLF line endings (a file saved on Windows) are read as LF.
     */
    static Doc parse(String name, String text)
    {
        text = text.replace("\r\n", "\n");
        if (text.startsWith("---\n"))
        {
            int end = text.indexOf("\n---\n", 3);
            if (end >= 0)
            {
                Map<?, ?> meta = readYaml(text.substring(4, end + 1));
                String body = text.substring(end + 5);
                if (body.startsWith("\n"))
                {
                    body = body.substring(1);
                }
                Object modified = meta.get("lastModified");
                return new Doc(name, tagsOf(meta.get("tags")), Boolean.TRUE.equals(meta.get("core")),
                               modified == null ? null : modified.toString(), body);
            }
        }
        return new Doc(name, List.of(), false, null, text);
    }

    /**
     * The file content: the same layout mcp-memory-bank writes.
     */
    String render()
    {
        StringBuilder out = new StringBuilder("---\ncore: ").append(core).append('\n');
        if (lastModified != null)
        {
            out.append("lastModified: '").append(lastModified).append("'\n");
        }
        out.append(tags.isEmpty() ? "tags: []\n" : "tags:\n");
        for (String tag : tags)
        {
            out.append("- ").append(PLAIN_SCALAR.matcher(tag).matches() ? tag : "'" + tag.replace("'", "''") + "'").append('\n');
        }
        return out.append("---\n\n").append(body).toString();
    }

    /**
     * First 12 hex chars of SHA-1 of {@link #render()}.
     */
    String revision()
    {
        try
        {
            byte[] hash = MessageDigest.getInstance("SHA-1").digest(render().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 12);
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException(e);
        }
    }

    int size()
    {
        return render().getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * How the document is shown in the prime context; also the unit of the core budget.
     */
    String contextBlock()
    {
        return "## " + name + "\n\n" + body.strip() + "\n\n";
    }

    /**
     * lastModified as an instant, null if absent or unparsable.
     */
    Instant modifiedAt()
    {
        try
        {
            return lastModified == null ? null : Instant.parse(lastModified);
        }
        catch (DateTimeParseException e)
        {
            return null;
        }
    }

    static String stamp(Instant now)
    {
        return now.truncatedTo(ChronoUnit.SECONDS).toString();
    }

    private static int primeRank(Doc doc)
    {
        return switch (doc.name())
        {
            case "context.md" -> 0;
            case "activeTask.md" -> 1;
            default -> 2;
        };
    }

    /**
     * Why the frontmatter of the file text can't be read (parse then drops core and tags), null if it is fine or absent.
     */
    static String frontmatterError(String text)
    {
        text = text.replace("\r\n", "\n");
        int end = text.startsWith("---\n") ? text.indexOf("\n---\n", 3) : -1;
        String yaml = end < 0 ? "" : text.substring(4, end + 1);
        try
        {
            if (!yaml.isBlank())
            {
                YAML.readValue(yaml, Map.class);
            }
            return null;
        }
        catch (Exception e)
        {
            return String.valueOf(e.getMessage()).lines().findFirst().orElse("invalid YAML");
        }
    }

    private static Map<?, ?> readYaml(String yaml)
    {
        try
        {
            Map<?, ?> meta = yaml.isBlank() ? null : YAML.readValue(yaml, Map.class);
            return meta == null ? Map.of() : meta;
        }
        catch (Exception e)
        {
            return Map.of();
        }
    }

    private static List<String> tagsOf(Object tags)
    {
        if (tags instanceof List<?> list)
        {
            return list.stream().map(String::valueOf).toList();
        }
        return tags == null ? List.of() : List.of(tags.toString());
    }
}
