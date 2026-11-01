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
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.poifs.crypt.EncryptionInfo;
import org.apache.poi.poifs.crypt.EncryptionMode;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.sl.usermodel.SlideShow;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentTextExtractorTest {

    private final ParseProperties properties = new ParseProperties();
    private final DocumentTextExtractor extractor = new DocumentTextExtractor(properties);

    @Test
    void readsJsonAsKnowledgeBaseTextWithoutExecutingContent() throws IOException {
        String content = "{\"name\":\"示例\",\"nested\":{\"enabled\":true}}";
        var result = extractor.extract("data.json", "application/json",
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        assertFalse(result.isSkipped());
        assertEquals(content, result.getSegments().get(0).text());
    }

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
    void readsAlreadyLoadedBytesWithoutChangingTheSource() throws IOException {
        byte[] source = "原件缓存测试".getBytes(StandardCharsets.UTF_8);
        byte[] before = source.clone();

        ExtractedText result = extractor.extractSource("notes.txt", "text/plain", source);

        assertEquals("原件缓存测试", result.getSegments().get(0).text());
        assertArrayEquals(before, source);
    }

    @Test
    void byteArrayEntryPointAlsoEnforcesTheConfiguredLimit() {
        properties.setMaxBytes(32);

        assertThrows(IOException.class, () -> extractor.extractSource("notes.txt", "text/plain", new byte[33]));
        properties.setMaxBytes(0);

        assertThrows(IOException.class, () -> extractor.extractSource("notes.txt", "text/plain", new byte[0]));
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

    @ParameterizedTest
    @CsvSource({
            "csv,text/csv",
            "tsv,text/tab-separated-values",
            "doc,application/msword",
            "xls,application/vnd.ms-excel",
            "ppt,application/vnd.ms-powerpoint",
            "xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "pptx,application/vnd.openxmlformats-officedocument.presentationml.presentation"
    })
    void recognizesCommonSpaceDocumentsByExtensionOrMime(String extension, String mime) {
        assertTrue(DocumentTextExtractor.supported("file." + extension, "application/octet-stream"));
        assertTrue(DocumentTextExtractor.supported("FILE." + extension.toUpperCase(Locale.ROOT), null));
        assertTrue(DocumentTextExtractor.supported("file", mime));
        assertTrue(DocumentTextExtractor.supported("file", mime + "; charset=UTF-8"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"csv", "tsv"})
    void readsDelimitedTextWithoutChangingRowsOrExecutingFormulas(String extension) throws IOException {
        String separator = extension.equals("csv") ? "," : "\t";
        String content = "项目" + separator + "预算\r\n服务器" + separator + "1000\r\n合计" + separator + "=SUM(B2)";

        ExtractedText extracted = extractor.extract("budget." + extension, "application/octet-stream",
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));

        assertFalse(extracted.isSkipped());
        assertEquals(content.replace("\r\n", "\n"), extracted.getSegments().get(0).text());
    }

    @ParameterizedTest
    @ValueSource(strings = {"xls", "xlsx"})
    void readsAllWorksheetsAndCachedFormulaValues(String extension) throws IOException {
        ExtractedText extracted = extractor.extract("budget." + extension, "application/octet-stream", spreadsheet(extension));

        assertFalse(extracted.isSkipped());
        String text = extracted.getSegments().get(0).text();
        assertTrue(text.contains("Budget"));
        assertTrue(text.contains("服务器"));
        assertTrue(text.contains("1250"));
        assertTrue(text.contains("Summary"));
        // 使用已有计算结果，不执行公式或访问外部引用。
        assertTrue(text.contains("17"));
        assertFalse(text.contains("1+2"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ppt", "pptx"})
    void readsSlidesTablesAndGroupedText(String extension) throws IOException {
        ExtractedText extracted = extractor.extract("slides." + extension, "application/octet-stream", presentation(extension));

        assertFalse(extracted.isSkipped());
        String text = extracted.getSegments().get(0).text();
        assertTrue(text.contains("Project overview"));
        assertTrue(text.contains("Second slide"));
        assertTrue(text.contains("Table body"));
        assertTrue(text.contains("Grouped text"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"xls", "xlsx", "ppt", "pptx"})
    void officeDocumentsRespectTheTextLimit(String extension) throws IOException {
        properties.setMaxChars(4);

        ExtractedText extracted = extractor.extract("large." + extension, "application/octet-stream", office(extension));

        assertTrue(extracted.isSkipped());
        assertEquals("正文超过解析长度上限", extracted.getReason());
    }

    @ParameterizedTest
    @ValueSource(strings = {"csv", "tsv", "doc", "xls", "xlsx", "ppt", "pptx"})
    void addedFormatsRejectPdfContentWithTheWrongExtension(String extension) throws IOException {
        assertThrows(IOException.class, () -> extractor.extract("wrong." + extension,
                "application/octet-stream", pdf("wrong type")));
    }

    @Test
    void rejectsRenamedOfficeFormats() throws IOException {
        assertThrows(IOException.class, () -> extractor.extract("wrong.pptx", "application/octet-stream", spreadsheet("xlsx")));
        assertThrows(IOException.class, () -> extractor.extract("wrong.xls", "application/octet-stream", presentation("ppt")));
        assertThrows(IOException.class, () -> extractor.extract("wrong.doc", "application/octet-stream", spreadsheet("xls")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"xlsx", "pptx"})
    void skipsEncryptedOfficeDocuments(String extension) throws Exception {
        try (POIFSFileSystem fs = new POIFSFileSystem();
             ByteArrayInputStream source = office(extension);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var encryptor = new EncryptionInfo(EncryptionMode.agile).getEncryptor();
            encryptor.confirmPassword("test-password");

            try (var encrypted = encryptor.getDataStream(fs)) {
                source.transferTo(encrypted);
            }

            fs.writeFilesystem(output);
            ExtractedText extracted = extractor.extractSource("encrypted." + extension,
                    "application/octet-stream", output.toByteArray());

            assertTrue(extracted.isSkipped());
            assertEquals("文件已加密", extracted.getReason());
        }
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

    private static ByteArrayInputStream office(String extension) throws IOException {
        return extension.startsWith("xls") ? spreadsheet(extension) : presentation(extension);
    }

    private static ByteArrayInputStream spreadsheet(String extension) throws IOException {
        try (Workbook book = extension.equals("xlsx") ? new XSSFWorkbook() : new HSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var row = book.createSheet("Budget").createRow(0);
            row.createCell(0).setCellValue("服务器");
            row.createCell(1).setCellValue(1250);

            var formula = book.createSheet("Summary").createRow(0).createCell(0);
            formula.setCellFormula("1+2");
            formula.setCellValue(17);
            book.write(output);

            return new ByteArrayInputStream(output.toByteArray());
        }
    }

    private static ByteArrayInputStream presentation(String extension) throws IOException {
        try (SlideShow<?, ?> deck = extension.equals("pptx") ? new XMLSlideShow() : new HSLFSlideShow();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            deck.createSlide().createTextBox().setText("Project overview");
            var second = deck.createSlide();
            second.createTextBox().setText("Second slide");
            second.createTable(1, 2).getCell(0, 0).setText("Table body");
            second.createGroup().createTextBox().setText("Grouped text");
            deck.write(output);

            return new ByteArrayInputStream(output.toByteArray());
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

    @Test
    void multiPagePdfCleansRepeatedHeadersAndPageNumbers() throws IOException {
        List<List<String>> pages = List.of(
                List.of("CONFIDENTIAL REPORT", "Chapter 1 Introduction content", "Page 1 of 4"),
                List.of("CONFIDENTIAL REPORT", "Chapter 2 Architecture details", "Page 2 of 4"),
                List.of("CONFIDENTIAL REPORT", "Chapter 3 Deployment steps", "Page 3 of 4"),
                List.of("CONFIDENTIAL REPORT", "Chapter 4 Summary notes", "Page 4 of 4")
        );

        ExtractedText extracted = extractor.extract("report.pdf", "application/pdf", multiPagePdf(pages));
        assertFalse(extracted.isSkipped());
        assertEquals(4, extracted.getSegments().size());

        for (int i = 0; i < 4; i++) {
            String text = extracted.getSegments().get(i).text();
            assertFalse(text.contains("CONFIDENTIAL REPORT"), "Page " + (i + 1) + " should not contain header");
            assertFalse(text.contains("Page " + (i + 1) + " of 4"), "Page " + (i + 1) + " should not contain page number");
            assertTrue(text.contains("Chapter " + (i + 1)));
        }
    }

    @Test
    void pdfDoesNotCleanHeadersWhenUnderThreePages() throws IOException {
        List<List<String>> pages = List.of(
                List.of("CONFIDENTIAL REPORT", "First page body text"),
                List.of("CONFIDENTIAL REPORT", "Second page body text")
        );

        ExtractedText extracted = extractor.extract("short.pdf", "application/pdf", multiPagePdf(pages));
        assertFalse(extracted.isSkipped());
        assertEquals(2, extracted.getSegments().size());
        assertTrue(extracted.getSegments().get(0).text().contains("CONFIDENTIAL REPORT"));
        assertTrue(extracted.getSegments().get(1).text().contains("CONFIDENTIAL REPORT"));
    }

    @Test
    void docxExcludesHeadersAndFooters() throws IOException {
        ExtractedText extracted = extractor.extract(
                "doc_with_hf.docx",
                "application/octet-stream",
                docxWithHeaderFooter("SECRET_HEADER", "MAIN_BODY_CONTENT", "SECRET_FOOTER")
        );

        assertFalse(extracted.isSkipped());
        String text = extracted.getSegments().get(0).text();
        assertTrue(text.contains("MAIN_BODY_CONTENT"));
        assertFalse(text.contains("SECRET_HEADER"));
        assertFalse(text.contains("SECRET_FOOTER"));
    }

    private static ByteArrayInputStream multiPagePdf(List<List<String>> pagesLines) throws IOException {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (List<String> lines : pagesLines) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    stream.beginText();
                    stream.setFont(PDType1Font.HELVETICA, 12);
                    stream.newLineAtOffset(50, 700);
                    for (int i = 0; i < lines.size(); i++) {
                        if (i > 0) {
                            stream.newLineAtOffset(0, -20);
                        }
                        stream.showText(lines.get(i));
                    }
                    stream.endText();
                }
            }
            document.save(out);
            return new ByteArrayInputStream(out.toByteArray());
        }
    }

    private static ByteArrayInputStream docxWithHeaderFooter(String header, String body, String footer) throws IOException {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var docHeader = document.createHeader(org.apache.poi.wp.usermodel.HeaderFooterType.DEFAULT);
            docHeader.createParagraph().createRun().setText(header);
            document.createParagraph().createRun().setText(body);
            var docFooter = document.createFooter(org.apache.poi.wp.usermodel.HeaderFooterType.DEFAULT);
            docFooter.createParagraph().createRun().setText(footer);
            document.write(out);
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
