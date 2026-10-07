package asia.creat.teamdocsbackend.parse;

import asia.creat.entity.DocumentContent;
import asia.creat.parse.TextChunker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextChunkerTest {

    @Test
    void blankTextProducesNoChunks() {
        assertTrue(TextChunker.chunk(" \r\n\t ", 1, 1000, 120).isEmpty());
    }

    @Test
    void overlapAdvancesAndKeepsPageOffset() {
        String text = "甲".repeat(2500);
        List<DocumentContent> chunks = TextChunker.chunk(text, 3, 1000, 120);

        assertEquals(3, chunks.size());
        assertEquals(0, chunks.get(0).getCharStart());
        assertEquals(1000, chunks.get(0).getCharEnd());
        assertEquals(880, chunks.get(1).getCharStart());
        assertEquals(1880, chunks.get(1).getCharEnd());
        assertEquals(1760, chunks.get(2).getCharStart());
        assertEquals(2500, chunks.get(2).getCharEnd());
        assertEquals(3, chunks.get(2).getPageNumber());
        assertEquals(1000, chunks.get(0).getTokenCount());
        assertEquals(text.substring(1760, 2500), chunks.get(2).getContent());
    }

    @Test
    void englishIsEstimatedSmallerThanCharacterCount() {
        DocumentContent chunk = TextChunker.chunk("abcd efgh", null, 1000, 120).get(0);

        assertTrue(chunk.getTokenCount() < chunk.getContent().length());
    }

    @Test
    void illegalOverlapIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> TextChunker.chunk("abc", null, 100, 100));
    }

    @Test
    void prefersParagraphBoundaryAndKeepsExactOffsets() {
        String text = "第一段".repeat(200) + "\n" + "第二段".repeat(300);
        List<DocumentContent> chunks = TextChunker.chunk(text, null, 1000, 120);

        assertEquals(601, chunks.get(0).getCharEnd());

        for (DocumentContent chunk : chunks) {
            assertEquals(text.substring(chunk.getCharStart(), chunk.getCharEnd()), chunk.getContent());
        }
    }

    @Test
    void doesNotSplitSupplementaryCharacters() {
        String text = "甲😀𠀀".repeat(10);

        for (DocumentContent chunk : TextChunker.chunk(text, null, 4, 1)) {
            assertTrue(!Character.isLowSurrogate(chunk.getContent().charAt(0)));
            assertTrue(!Character.isHighSurrogate(chunk.getContent().charAt(chunk.getContent().length() - 1)));
            assertEquals(text.substring(chunk.getCharStart(), chunk.getCharEnd()), chunk.getContent());
        }

        assertEquals(2, TextChunker.estimateTokens("𠀀甲"));
    }
}
