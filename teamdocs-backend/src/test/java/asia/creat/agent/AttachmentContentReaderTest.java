package asia.creat.agent;

import org.junit.jupiter.api.Test;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class AttachmentContentReaderTest {
    private final AttachmentContentReader reader = new AttachmentContentReader();
    private String text(AttachmentMessage.Part part) {
        return new String(Base64.getDecoder().decode(part.base64()), StandardCharsets.UTF_8);
    }

    @Test void textFilesAreDecodedNotSentAsNativeFileObjects() throws Exception {
        for (String name : new String[]{"a.json", "a.md", "a.csv", "a.tsv"}) {
            var parts = reader.read(name, "text/plain", "hello 中文".getBytes(StandardCharsets.UTF_8));
            assertEquals("text/plain", parts.get(0).mime());
            assertTrue(text(parts.get(0)).contains("中文"));
        }
        assertThrows(Exception.class, () -> reader.read("large.txt", "text/plain", "x".repeat(100001).getBytes(StandardCharsets.UTF_8)));
    }

    @Test void officeDocumentsKeepParagraphSheetAndSlideSources() throws Exception {
        try (var document = new XWPFDocument(); var out = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("Word paragraph"); document.write(out);
            assertTrue(text(reader.read("a.docx", "application/octet-stream", out.toByteArray()).get(0)).contains("Word paragraph"));
        }
        try (var book = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var row = book.createSheet("Budget").createRow(0);
            row.createCell(0).setCellValue("Header"); row.createCell(1).setCellFormula("1+2"); book.write(out);
            String value = text(reader.read("a.xlsx", "application/octet-stream", out.toByteArray()).get(0));
            assertTrue(value.contains("Budget")); assertTrue(value.contains("A1: Header")); assertTrue(value.contains("1+2"));
        }
        try (var slides = new XMLSlideShow(); var out = new ByteArrayOutputStream()) {
            slides.createSlide().createTextBox().setText("Slide body"); slides.write(out);
            String value = text(reader.read("a.pptx", "application/octet-stream", out.toByteArray()).get(0));
            assertTrue(value.contains("幻灯片 1")); assertTrue(value.contains("Slide body"));
        }
    }

    @Test void pdfUsesPageTextAndRendersOnlyTextlessPages() throws Exception {
        try (var pdf = new PDDocument(); var out = new ByteArrayOutputStream()) {
            var page = new PDPage(); pdf.addPage(page); pdf.addPage(new PDPage());
            try (var content = new PDPageContentStream(pdf, page)) {
                content.beginText(); content.setFont(PDType1Font.HELVETICA, 12); content.newLineAtOffset(20, 700);
                content.showText("PDF text"); content.endText();
            }
            pdf.save(out);
            var parts = reader.read("a.pdf", "application/pdf", out.toByteArray());
            assertEquals(2, parts.size()); assertTrue(text(parts.get(0)).contains("PDF text"));
            assertTrue(text(parts.get(0)).contains("第 1 页"));
            assertEquals("image/png", parts.get(1).mime()); assertTrue(parts.get(1).name().contains("第 2 页"));
        }
    }

    @Test void scanLimitRejectsWholeDocumentInsteadOfSilentlyDroppingPages() throws Exception {
        try (var pdf = new PDDocument(); var out = new ByteArrayOutputStream()) {
            for (int i = 0; i < 9; i++) pdf.addPage(new PDPage());
            pdf.save(out);
            assertThrows(Exception.class, () -> reader.read("large.pdf", "application/pdf", out.toByteArray()));
        }
    }
}
