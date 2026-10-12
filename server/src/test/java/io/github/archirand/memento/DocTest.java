package io.github.archirand.memento;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocTest
{
    /** Byte-exact sample of a file written by mcp-memory-bank. */
    private static final String OLD_FORMAT = "---\ncore: true\nlastModified: '2026-08-14T06:58:10Z'\ntags:\n- context\n- constraints\n---\n\n# Context: klone-mobile\n\n## Проект\nМонорепа.\n";

    @Test
    void parseReadsMcpMemoryBankFrontmatter()
    {
        Doc doc = Doc.parse("context.md", OLD_FORMAT);

        assertTrue(doc.core(), "core flag must survive parsing");
        assertEquals(List.of("context", "constraints"), doc.tags(), "tags must keep their order");
        assertEquals("2026-08-14T06:58:10Z", doc.lastModified(), "lastModified must be read as is");
        assertEquals("# Context: klone-mobile\n\n## Проект\nМонорепа.\n", doc.body(), "body must start right after the blank line");
    }

    @Test
    void renderOfParsedOldFileIsByteIdentical()
    {
        assertEquals(OLD_FORMAT, Doc.parse("context.md", OLD_FORMAT).render(), "migration relies on an unchanged file format");
    }

    @Test
    void textWithoutFrontmatterBecomesWholeBody()
    {
        Doc doc = Doc.parse("notes.md", "just text\n");

        assertFalse(doc.core(), "a file without frontmatter is not core");
        assertEquals(List.of(), doc.tags(), "a file without frontmatter has no tags");
        assertEquals("just text\n", doc.body(), "nothing may be lost from a file without frontmatter");
    }

    @Test
    void revisionChangesWhenBodyChanges()
    {
        Doc doc = Doc.parse("context.md", OLD_FORMAT);
        Doc edited = new Doc(doc.name(), doc.tags(), doc.core(), doc.lastModified(), doc.body() + "more\n");

        assertEquals(12, doc.revision().length(), "revision is 12 hex chars");
        assertNotEquals(doc.revision(), edited.revision(), "any content change must change the revision");
    }

    @Test
    void tagThatNeedsQuotingSurvivesRoundTrip()
    {
        Doc doc = new Doc("a.md", List.of("c++: core"), false, null, "x");

        assertEquals(List.of("c++: core"), Doc.parse("a.md", doc.render()).tags(), "special characters in tags must be quoted, not lost");
    }

    @Test
    void fileWithWindowsLineEndingsKeepsItsFrontmatter()
    {
        Doc doc = Doc.parse("context.md", OLD_FORMAT.replace("\n", "\r\n"));

        assertTrue(doc.core(), "a file saved with CRLF must stay core");
        assertEquals(List.of("context", "constraints"), doc.tags(), "a file saved with CRLF must keep its tags");
        assertEquals(OLD_FORMAT, doc.render(), "the next write must store the document with LF");
    }
}
