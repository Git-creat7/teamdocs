package asia.creat.teamdocsbackend.parse;

import asia.creat.config.ParseProperties;
import asia.creat.parse.DocumentTextExtractor;
import asia.creat.parse.ExtractedText;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentTextExtractorTest {

    private final ParseProperties properties = new ParseProperties();
    private final DocumentTextExtractor extractor = new DocumentTextExtractor(properties);

    @Test
    void readsUtf8Text() throws IOException {
        ExtractedText extracted = extractor.extract(
                "笔记.md",
                "text/plain",
                new ByteArrayInputStream("上线检查\n清单".getBytes(StandardCharsets.UTF_8))
        );

        assertFalse(extracted.isSkipped());
        assertEquals(1, extracted.getSegments().size());
        assertEquals("上线检查\n清单", extracted.getSegments().get(0).text());
        assertEquals(null, extracted.getSegments().get(0).pageNumber());
    }

    @Test
    void rejectsMalformedUtf8() {
        IOException error = assertThrows(IOException.class, () -> extractor.extract(
                "notes.txt",
                "text/plain",
                new ByteArrayInputStream(new byte[]{(byte) 0x80})
        ));
        assertTrue(error.getMessage().contains("UTF-8"));
    }

    @Test
    void skipsOversizedTextAndUnsupportedFiles() throws IOException {
        properties.setMaxChars(4);
        ExtractedText oversized = extractor.extract(
                "notes.txt",
                "text/plain",
                new ByteArrayInputStream("你好世界啊".getBytes(StandardCharsets.UTF_8))
        );
        assertTrue(oversized.isSkipped());
        assertEquals("正文超过解析长度上限", oversized.getReason());

        ExtractedText image = extractor.extract(
                "photo.png",
                "image/png",
                new ByteArrayInputStream(new byte[]{1, 2, 3})
        );
        assertTrue(image.isSkipped());
        assertEquals("未配置图像理解服务", image.getReason());
    }

    @Test
    void skipsEmptyText() throws IOException {
        ExtractedText extracted = extractor.extract(
                "empty.txt",
                "text/plain",
                new ByteArrayInputStream(" \n".getBytes(StandardCharsets.UTF_8))
        );
        assertTrue(extracted.isSkipped());
        assertEquals("没有可提取文本", extracted.getReason());
    }

    @Test
    void readsPdfPageTextAndSkipsBlankPdf() throws IOException {
        ExtractedText extracted = extractor.extract("report.pdf", "application/pdf", pdf("Hello TeamDocs"));
        assertFalse(extracted.isSkipped());
        assertEquals(1, extracted.getSegments().get(0).pageNumber());
        assertTrue(extracted.getSegments().get(0).text().contains("Hello TeamDocs"));

        ExtractedText blank = extractor.extract("blank.pdf", "application/pdf", pdf(null));
        assertTrue(blank.isSkipped());
        assertEquals("没有可提取文本", blank.getReason());
    }

    @Test
    void readsDocx() throws IOException {
        ExtractedText extracted = extractor.extract(
                "spec.docx",
                "application/octet-stream",
                docx("上线检查清单")
        );
        assertFalse(extracted.isSkipped());
        assertTrue(extracted.getSegments().get(0).text().contains("上线检查清单"));
    }

    @Test
    void rejectsBinaryContentDisguisedAsText() throws IOException {
        assertThrows(IOException.class, () -> extractor.extract("notes.txt", "text/plain", pdf("hidden PDF")));
        assertThrows(IOException.class, () -> extractor.extract("notes.docx", "application/octet-stream", pdf("wrong type")));
    }

    @Test
    void rejectsHighlyCompressedDocx() throws IOException {
        ByteArrayInputStream bomb = docx("a".repeat(1_000_000));
        assertThrows(IOException.class, () -> extractor.extract("bomb.docx", "application/octet-stream", bomb));
    }

    @Test
    void encryptedPdfIsSkipped() throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.protect(new StandardProtectionPolicy("owner-password", "user-password", new AccessPermission()));
            document.save(output);
            ExtractedText result = extractor.extract("encrypted.pdf", "application/pdf", new ByteArrayInputStream(output.toByteArray()));
            assertTrue(result.isSkipped());
            assertEquals("文件已加密", result.getReason());
        }
    }

    private static ByteArrayInputStream pdf(String text) throws IOException {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            if (text != null) {
                try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    stream.beginText();
                    stream.setFont(PDType1Font.HELVETICA, 12);
                    stream.newLineAtOffset(50, 700);
                    stream.showText(text);
                    stream.endText();
                }
            }
            document.save(out);
            return new ByteArrayInputStream(out.toByteArray());
        }
    }

    private static ByteArrayInputStream docx(String text) throws IOException {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText(text);
            document.write(out);
            return new ByteArrayInputStream(out.toByteArray());
        }
    }
}
