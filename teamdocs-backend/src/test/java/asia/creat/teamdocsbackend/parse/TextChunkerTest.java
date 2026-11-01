package asia.creat.teamdocsbackend.parse;

import asia.creat.entity.DocumentContent;
import asia.creat.parse.TextChunker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void headingHierarchyTrackedAcrossChunks() {
        String text = """
                # 项目架构指南
                这是前言说明。
                
                ## 后端设计
                后端采用 Spring Boot 与 Java 17。
                
                ### 数据存储
                使用 MySQL 存储元数据。
                
                ## 前端设计
                前端基于 Vue 3 构建。
                """;

        List<DocumentContent> chunks = TextChunker.chunk(text, null, 50, 10);
        assertFalse(chunks.isEmpty());

        for (DocumentContent chunk : chunks) {
            assertEquals(text.trim().substring(chunk.getCharStart(), chunk.getCharEnd()), chunk.getContent());
            if (chunk.getContent().contains("数据存储")) {
                assertEquals("项目架构指南 > 后端设计 > 数据存储", chunk.getHeading());
            } else if (chunk.getContent().contains("前端基于")) {
                assertEquals("项目架构指南 > 前端设计", chunk.getHeading());
            }
        }
    }

    @Test
    void fencedCodeBlockProtection() {
        String text = "前言文本\n\n```java\npublic class Demo {\n    public static void main(String[] args) {\n        System.out.println(\"Hello World\");\n    }\n}\n```\n\n后续正文说明。";
        List<DocumentContent> chunks = TextChunker.chunk(text, null, 100, 20);

        for (DocumentContent chunk : chunks) {
            assertEquals(text.substring(chunk.getCharStart(), chunk.getCharEnd()), chunk.getContent());
            // 如果包含了代码块开始，不应在单行中间将代码块切断
            if (chunk.getContent().contains("public class Demo")) {
                assertTrue(chunk.getContent().contains("```java"));
            }
        }
    }

    @Test
    void tableRowProtection() {
        String text = "表格前文本说明\n\n| 列1 | 列2 | 列3 |\n| --- | --- | --- |\n| 单元格A1 | 单元格A2 | 单元格A3 |\n| 单元格B1 | 单元格B2 | 单元格B3 |\n\n表格后文本说明。";
        List<DocumentContent> chunks = TextChunker.chunk(text, null, 80, 15);

        for (DocumentContent chunk : chunks) {
            assertEquals(text.substring(chunk.getCharStart(), chunk.getCharEnd()), chunk.getContent());
            // 确保切分时行完整（不切入列中间）
            String[] lines = chunk.getContent().split("\n");
            for (String line : lines) {
                if (line.contains("|") && !line.trim().isEmpty()) {
                    assertTrue(line.trim().startsWith("|") && line.trim().endsWith("|"));
                }
            }
        }
    }
}
