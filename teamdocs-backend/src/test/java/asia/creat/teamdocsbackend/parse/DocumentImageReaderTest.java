package asia.creat.teamdocsbackend.parse;

import asia.creat.config.ParseProperties;
import asia.creat.config.VisionProperties;
import asia.creat.parse.DocumentImageReader;
import asia.creat.parse.DocumentTextExtractor;
import asia.creat.parse.ExtractedText;
import asia.creat.parse.ImageUnderstandingService;
import asia.creat.retrieval.RetrievalHttp;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocumentImageReaderTest {
    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private final ParseProperties parse = new ParseProperties();
    private final VisionProperties vision = new VisionProperties();
    private final DocumentImageReader reader = new DocumentImageReader(parse, vision);

    /** 预览保留 PNG 和 JPEG 原件，不暴露存储地址。 */
    @Test
    void preservesOriginalImageBytes() throws IOException {
        for (String format : List.of("png", "jpeg")) {
            byte[] original = image(format);
            var preview = reader.read("photo." + format, "image/" + format, input(original), "original");
            assertEquals("image/" + format, preview.contentType());
            assertArrayEquals(original, preview.content());
        }
    }

    /** TwelveMonkeys 为独立 WebP 提供解码器。 */
    @Test
    void registersWebpReader() {
        assertTrue(ImageIO.getImageReadersByFormatName("webp").hasNext());
        assertTrue(DocumentTextExtractor.supported("photo.webp", "image/webp"));
    }

    /** 真实 WebP 字节必须能解码与转译，不能只检查插件是否注册。 */
    @Test
    void decodesRealWebpFixture() throws IOException {
        byte[] webp = java.util.Base64.getDecoder().decode(
                "UklGRh4CAABXRUJQVlA4WAoAAAAgAAAAAQAAAQAASUNDUMgBAAAAAAHIAAAAAAQwAABtbnRyUkdCIFhZWiAH4AABAAEAAAAAAABhY3NwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAQAA9tYAAQAAAADTLQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAlkZXNjAAAA8AAAACRyWFlaAAABFAAAABRnWFlaAAABKAAAABRiWFlaAAABPAAAABR3dHB0AAABUAAAABRyVFJDAAABZAAAAChnVFJDAAABZAAAAChiVFJDAAABZAAAAChjcHJ0AAABjAAAADxtbHVjAAAAAAAAAAEAAAAMZW5VUwAAAAgAAAAcAHMAUgBHAEJYWVogAAAAAAAAb6IAADj1AAADkFhZWiAAAAAAAABimQAAt4UAABjaWFlaIAAAAAAAACSgAAAPhAAAts9YWVogAAAAAAAA9tYAAQAAAADTLXBhcmEAAAAAAAQAAAACZmYAAPKnAAANWQAAE9AAAApbAAAAAAAAAABtbHVjAAAAAAAAAAEAAAAMZW5VUwAAACAAAAAcAEcAbwBvAGcAbABlACAASQBuAGMALgAgADIAMAAxADZWUDggMAAAANABAJ0BKgIAAgABQCYloAJ0ugH4AAOwAP7y63/82BXNc+/3/9Lg/S4P0uD/0pAAAA==");
        var preview = reader.read("photo.webp", "image/webp", input(webp), "original");
        assertEquals("image/webp", preview.contentType());
        assertArrayEquals(webp, preview.content());
        BufferedImage decoded = ImageIO.read(input(webp));
        assertEquals(2, decoded.getWidth());
        assertEquals(2, decoded.getHeight());
        var extracted = new DocumentTextExtractor(parse, model()).extract("photo.webp", "image/webp", input(webp));
        assertFalse(extracted.isSkipped());
        assertEquals("original", extracted.getSegments().get(0).imageRef());
    }

    /** 原件字节、图片字节和真实 MIME 都受限制。 */
    @Test
    void rejectsOversizedOriginalAndDisguisedImage() throws IOException {
        byte[] png = image("png");
        parse.setMaxBytes(png.length - 1);
        assertThrows(IOException.class, () -> reader.read("photo.png", "image/png", input(png), "original"));
        parse.setMaxBytes(png.length);
        vision.setMaxImageBytes(png.length - 1);
        assertThrows(IOException.class, () -> reader.read("photo.png", "image/png", input(png), "original"));
        vision.setMaxImageBytes(png.length);
        assertEquals("image/png", reader.read("photo.jpg", "image/jpeg", input(png), "original").contentType());
        assertThrows(IOException.class, () -> reader.read("photo.png", "image/png", input(png), "pdf:1"));
    }

    /** 伪造的巨大尺寸在解码像素前被拒绝。 */
    @Test
    void checksHeaderPixelsBeforeDecoding() throws IOException {
        byte[] png = image("png");
        ByteBuffer.wrap(png).putInt(16, 100000).putInt(20, 100000);
        CRC32 crc = new CRC32();
        crc.update(png, 12, 17);
        ByteBuffer.wrap(png).putInt(29, (int) crc.getValue());
        IOException error = assertThrows(IOException.class, () -> reader.read("photo.png", "image/png", input(png), "original"));
        assertTrue(error.getMessage().contains("像素"));
    }

    /** 扫描 PDF 使用一基页码和有界渲染。 */
    @Test
    void rendersPdfPageAndRejectsInvalidReferences() throws IOException {
        byte[] pdf = pdf(2, true, false);
        vision.setMaxDimension(240);
        var preview = reader.read("scan.pdf", "application/pdf", input(pdf), "pdf:2");
        BufferedImage rendered = ImageIO.read(input(preview.content()));
        assertNotNull(rendered);
        assertTrue(rendered.getWidth() <= 240);
        assertTrue(rendered.getHeight() <= 240);
        assertTrue(preview.content().length <= vision.getMaxImageBytes());
        for (String ref : List.of("pdf:0", "pdf:-1", "pdf:3", "pdf:01", "pdf:999999999999", "https://example.invalid/scan")) {
            assertThrows(IOException.class, () -> reader.read("scan.pdf", "application/pdf", input(pdf), ref));
        }
        vision.setMaxPixels(8);
        assertThrows(IOException.class, () -> reader.read("scan.pdf", "application/pdf", input(pdf), "pdf:1"));
    }

    /** DOCX 按稳定部件引用读取内嵌图片。 */
    @Test
    void readsOnlyNamedEmbeddedPart() throws Exception {
        byte[] document = docx(true, false);
        var preview = reader.read("spec.docx", DOCX, input(document), "docx:/word/media/image1.png");
        assertArrayEquals(image("png"), preview.content());
        for (String ref : List.of("docx:/word/media/missing.png", "docx:/word/document.xml",
                "docx:/word/media/../document.xml", "docx:https://example.invalid/photo.png", "original")) {
            assertThrows(IOException.class, () -> reader.read("spec.docx", DOCX, input(document), ref));
        }
        vision.setMaxImageBytes(image("png").length - 1);
        assertThrows(IOException.class, () -> reader.read("spec.docx", DOCX, input(document), "docx:/word/media/image1.png"));
    }

    /** 外部图片关系在预览和提取中都不能被读取。 */
    @Test
    void rejectsExternalDocxImagesBeforeModelCalls() throws Exception {
        byte[] document = docx(true, true);
        ImageUnderstandingService model = model();
        assertThrows(IOException.class, () -> reader.read("spec.docx", DOCX, input(document), "docx:/word/media/image1.png"));
        assertThrows(IOException.class, () -> new DocumentTextExtractor(parse, model).extract("spec.docx", DOCX, input(document)));
        verify(model, never()).describe(any(), anyString());
    }

    /** 未启用视觉时，图片明确跳过，普通文档沿用文本路径。 */
    @Test
    void disabledVisionKeepsOriginalTextPaths() throws Exception {
        ImageUnderstandingService disabled = spy(new ImageUnderstandingService(vision, mock(RetrievalHttp.class)));
        DocumentTextExtractor extractor = new DocumentTextExtractor(parse, disabled);
        ExtractedText image = extractor.extract("photo.png", "image/png", input(image("png")));
        assertTrue(image.isSkipped());
        assertEquals("未配置图像理解服务", image.getReason());
        ExtractedText scan = extractor.extract("scan.pdf", "application/pdf", input(pdf(1, true, false)));
        assertTrue(scan.isSkipped());
        ExtractedText textPdf = extractor.extract("text.pdf", "application/pdf", input(pdf(1, false, true)));
        assertTrue(textPdf.getSegments().get(0).text().contains("Hello TeamDocs"));
        assertNull(textPdf.getSegments().get(0).imageRef());
        ExtractedText textDocx = extractor.extract("spec.docx", DOCX, input(docx(true, false)));
        assertTrue(textDocx.getSegments().get(0).text().contains("Hello TeamDocs"));
        assertTrue(textDocx.getSegments().stream().allMatch(segment -> segment.imageRef() == null));
        verify(disabled, never()).describe(any(), anyString());
    }

    /** 每幅独立图片生成带位置和来源标记的片段。 */
    @Test
    void extractsStandaloneImagesWithOriginalReference() throws IOException {
        ImageUnderstandingService model = model();
        DocumentTextExtractor extractor = new DocumentTextExtractor(parse, model);
        for (String format : List.of("png", "jpeg")) {
            ExtractedText result = extractor.extract("photo." + format, "image/" + format, input(image(format)));
            assertFalse(result.isSkipped());
            assertEquals(1, result.getSegments().size());
            var segment = result.getSegments().get(0);
            assertEquals("original", segment.imageRef());
            assertEquals("原图", segment.imageLabel());
            assertNull(segment.pageNumber());
            assertTrue(segment.text().startsWith("图像描述（模型生成）"));
        }
        verify(model, times(2)).describe(any(), anyString());
        assertThrows(IOException.class, () -> extractor.extract("photo.jpg", "image/jpeg", input(image("png"))));
    }

    /** 纯文字 PDF 不调用模型，扫描页和混合页各有独立图像片段。 */
    @Test
    void extractsOnlyPdfPagesNeedingVision() throws IOException {
        ImageUnderstandingService model = model();
        DocumentTextExtractor extractor = new DocumentTextExtractor(parse, model);
        assertFalse(extractor.extract("text.pdf", "application/pdf", input(pdf(1, false, true))).isSkipped());
        verify(model, never()).describe(any(), anyString());
        for (boolean withText : List.of(false, true)) {
            ExtractedText result = extractor.extract("scan.pdf", "application/pdf", input(pdf(1, true, withText)));
            var images = result.getSegments().stream().filter(segment -> segment.imageRef() != null).toList();
            assertEquals(1, images.size());
            assertEquals("pdf:1", images.get(0).imageRef());
            assertEquals(1, images.get(0).pageNumber());
            assertEquals("PDF 第 1 页", images.get(0).imageLabel());
            if (withText) assertTrue(result.getSegments().get(0).text().contains("Hello TeamDocs"));
        }
        verify(model, times(2)).describe(any(), anyString());
    }

    /** 重复图片按段落和表格保留不同位置，不按部件去重。 */
    @Test
    void preservesRepeatedDocxPictureLocations() throws Exception {
        ImageUnderstandingService model = model();
        ExtractedText result = new DocumentTextExtractor(parse, model).extract("spec.docx", DOCX, input(docx(true, false)));
        var images = result.getSegments().stream().filter(segment -> segment.imageRef() != null).toList();
        assertEquals(2, images.size());
        assertEquals("docx:/word/media/image1.png", images.get(0).imageRef());
        assertEquals(images.get(0).imageRef(), images.get(1).imageRef());
        assertNotEquals(images.get(0).imageLabel(), images.get(1).imageLabel());
        assertTrue(images.get(1).imageLabel().contains("表格"));
        verify(model, times(2)).describe(any(), eq("image/png"));
    }

    /** 超出图片数量上限时保留文本和上限内已成功的图片。 */
    @Test
    void limitsImageAttemptsWithoutDiscardingDocumentText() throws Exception {
        ImageUnderstandingService model = model();
        vision.setMaxImages(1);
        DocumentTextExtractor extractor = new DocumentTextExtractor(parse, model);
        for (ExtractedText result : List.of(
                extractor.extract("scan.pdf", "application/pdf", input(pdf(2, true, false))),
                extractor.extract("spec.docx", DOCX, input(docx(true, false))))) {
            assertFalse(result.isSkipped());
            assertEquals(1, result.getSegments().stream().filter(segment -> segment.imageRef() != null).count());
            assertTrue(result.getSegments().stream().anyMatch(segment -> segment.text().contains("数量上限")));
        }
        verify(model, times(2)).describe(any(), anyString());
    }

    /** 已知正文超限不调用模型，描述超限也不发布部分结果。 */
    @Test
    void enforcesTotalCharacterLimit() throws IOException {
        ImageUnderstandingService model = model();
        DocumentTextExtractor extractor = new DocumentTextExtractor(parse, model);
        parse.setMaxChars(2);
        ExtractedText text = extractor.extract("mixed.pdf", "application/pdf", input(pdf(1, true, true)));
        assertTrue(text.isSkipped());
        verify(model, never()).describe(any(), anyString());
        ExtractedText description = extractor.extract("photo.png", "image/png", input(image("png")));
        assertTrue(description.isSkipped());
        assertEquals("没有可用正文，图片理解失败或受限", description.getReason());
        assertTrue(description.getSegments().isEmpty());
        verify(model).describe(any(), anyString());
    }

    /** 显示名称改变扩展名后，仍按可信来源引用读取原始格式。 */
    @Test
    void renamedDocumentsKeepTheirOriginalImageReferences() throws Exception {
        assertEquals("image/png", reader.read("renamed.pdf", "image/png", input(image("png")), "original").contentType());
        assertNotNull(reader.read("renamed.png", "application/pdf", input(pdf(1, true, false)), "pdf:1").content());
        assertArrayEquals(image("png"), reader.read("renamed.txt", DOCX, input(docx(true, false)),
                "docx:/word/media/image1.png").content());
    }

    /** 巨大内联图先按元数据拒绝，不先进入 Flate 解压。 */
    @Test
    void checksInlineImageDimensionsBeforeFilterDecoding() throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.appendRawCommands("BI /W 100000 /H 100000 /BPC 8 /CS /RGB /F /FlateDecode ID invalid EI\n");
            }
            document.save(output);
            IOException error = assertThrows(IOException.class,
                    () -> reader.read("scan.pdf", "application/pdf", input(output.toByteArray()), "pdf:1"));
            assertTrue(error.getMessage().contains("像素"));
        }
    }

    /** DOCX 页眉和页脚内嵌图片也保留各自的来源位置。 */
    @Test
    void extractsHeaderAndFooterPictures() throws Exception {
        byte[] bytes;
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("正文");
            var type = org.apache.poi.wp.usermodel.HeaderFooterType.DEFAULT;
            document.createHeader(type).createParagraph().createRun().addPicture(input(image("png")),
                    XWPFDocument.PICTURE_TYPE_PNG, "header.png", Units.toEMU(16), Units.toEMU(8));
            document.createFooter(type).createParagraph().createRun().addPicture(input(image("png")),
                    XWPFDocument.PICTURE_TYPE_PNG, "footer.png", Units.toEMU(16), Units.toEMU(8));
            document.write(output);
            bytes = output.toByteArray();
        }
        var result = new DocumentTextExtractor(parse, model()).extract("document.docx", DOCX, input(bytes));
        var labels = result.getSegments().stream().filter(segment -> segment.imageRef() != null)
                .map(ExtractedText.Segment::imageLabel).toList();
        assertEquals(2, labels.size());
        assertTrue(labels.stream().anyMatch(label -> label.contains("页眉")));
        assertTrue(labels.stream().anyMatch(label -> label.contains("页脚")));
    }

    /** 九张配图的普通文档仍能检索正文，模型最多处理前八张。 */
    @Test
    void ninePicturesKeepTextAndOnlyAnalyzeEight() throws Exception {
        ImageUnderstandingService model = model();
        var result = new DocumentTextExtractor(parse, model).extract("many.docx", DOCX, input(docxWithImages(9)));
        assertFalse(result.isSkipped());
        assertTrue(result.getSegments().stream().anyMatch(segment -> segment.text().contains("Hello TeamDocs")));
        assertEquals(8, result.getSegments().stream().filter(segment -> segment.imageRef() != null).count());
        verify(model, times(8)).describe(any(), anyString());
    }

    /** 单个视觉请求失败后继续处理其余图片，不丢原有文字。 */
    @Test
    void oneModelFailureDoesNotDiscardOtherImages() throws Exception {
        ImageUnderstandingService model = model();
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call -> {
            if (attempts.getAndIncrement() == 0) throw new IllegalStateException("模拟图像请求失败");
            return "成功的图像描述";
        }).when(model).describe(any(), anyString());
        var result = new DocumentTextExtractor(parse, model).extract("mixed.docx", DOCX, input(docxWithImages(3)));
        assertFalse(result.isSkipped());
        assertTrue(result.getSegments().stream().anyMatch(segment -> segment.text().contains("Hello TeamDocs")));
        assertEquals(2, result.getSegments().stream().filter(segment -> segment.imageRef() != null).count());
        assertTrue(result.getSegments().stream().anyMatch(segment -> segment.text().contains("1 幅图片解析失败")));
        assertEquals(3, attempts.get());
    }

    /** 图片全失败时仍保留文档文字，但纯图片不能以警告充当正文。 */
    @Test
    void allImagesFailWithoutPretendingEmptyContentIsReady() throws Exception {
        ImageUnderstandingService model = model();
        doThrow(new IllegalStateException("模拟失败")).when(model).describe(any(), anyString());
        DocumentTextExtractor extractor = new DocumentTextExtractor(parse, model);
        var document = extractor.extract("text.docx", DOCX, input(docxWithImages(2)));
        assertFalse(document.isSkipped());
        assertTrue(document.getSegments().stream().anyMatch(segment -> segment.text().contains("Hello TeamDocs")));
        var onlyImage = extractor.extract("image.png", "image/png", input(image("png")));
        assertTrue(onlyImage.isSkipped());
        assertTrue(onlyImage.getSegments().isEmpty());
    }

    /** 图片描述超预算只跳过该描述，不能清空普通正文。 */
    @Test
    void oversizedImageDescriptionsKeepOrdinaryText() throws Exception {
        ImageUnderstandingService model = model();
        parse.setMaxChars(80);
        doReturn("x".repeat(200)).when(model).describe(any(), anyString());
        var result = new DocumentTextExtractor(parse, model).extract("text.docx", DOCX, input(docxWithImages(2)));
        assertFalse(result.isSkipped());
        assertTrue(result.getSegments().stream().anyMatch(segment -> segment.text().contains("Hello TeamDocs")));
        assertTrue(result.getSegments().stream().noneMatch(segment -> segment.imageRef() != null));
    }

    /** 单个图片像素异常也只跳过该图，其他内嵌图和文字仍保留。 */
    @Test
    void invalidEmbeddedImageKeepsTextAndOtherPictures() throws Exception {
        byte[] bad = image("png");
        ByteBuffer.wrap(bad).putInt(16, 100000).putInt(20, 100000);
        CRC32 crc = new CRC32();
        crc.update(bad, 12, 17);
        ByteBuffer.wrap(bad).putInt(29, (int) crc.getValue());
        byte[] bytes;
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("Hello TeamDocs");
            for (byte[] picture : List.of(bad, image("png"))) {
                document.createParagraph().createRun().addPicture(input(picture), XWPFDocument.PICTURE_TYPE_PNG,
                        "picture.png", Units.toEMU(16), Units.toEMU(8));
            }
            document.write(output);
            bytes = output.toByteArray();
        }
        ImageUnderstandingService model = model();
        var result = new DocumentTextExtractor(parse, model).extract("mixed.docx", DOCX, input(bytes));
        assertFalse(result.isSkipped());
        assertTrue(result.getSegments().stream().anyMatch(segment -> segment.text().contains("Hello TeamDocs")));
        assertEquals(1, result.getSegments().stream().filter(segment -> segment.imageRef() != null).count());
        verify(model, times(1)).describe(any(), anyString());
    }

    /** PDF 页面上的坏图不能丢掉已经提取的页面文字。 */
    @Test
    void invalidPdfImageKeepsPageText() throws Exception {
        byte[] bytes;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(PDType1Font.HELVETICA, 12);
                content.newLineAtOffset(30, 700);
                content.showText("Hello TeamDocs");
                content.endText();
                content.appendRawCommands("BI /W 100000 /H 100000 /BPC 8 /CS /RGB /F /FlateDecode ID invalid EI\n");
            }
            document.save(output);
            bytes = output.toByteArray();
        }
        ImageUnderstandingService model = model();
        var result = new DocumentTextExtractor(parse, model).extract("mixed.pdf", "application/pdf", input(bytes));
        assertFalse(result.isSkipped());
        assertTrue(result.getSegments().stream().anyMatch(segment -> segment.text().contains("Hello TeamDocs")));
        verify(model, never()).describe(any(), anyString());
    }

    /** 运行版本或期限失效不是单图降级，必须立即停止后续外发。 */
    @Test
    void invalidatedParseDoesNotContinueAfterAnImageFailure() throws Exception {
        ImageUnderstandingService model = model();
        java.util.concurrent.atomic.AtomicBoolean invalid = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(call -> { invalid.set(true); throw new IllegalStateException("模拟失败"); })
                .when(model).describe(any(), anyString());
        try (var context = asia.creat.retrieval.RetrievalContext.open(Long.MAX_VALUE, () -> {
            if (invalid.get()) throw new IllegalStateException("解析版本已失效");
        }, hit -> { })) {
            assertThrows(IllegalStateException.class, () -> new DocumentTextExtractor(parse, model)
                    .extract("text.docx", DOCX, input(docxWithImages(3))));
        }
        verify(model, times(1)).describe(any(), anyString());
    }

    /** 生成含文字及指定数量配图的 DOCX。 */
    private static byte[] docxWithImages(int count) throws Exception {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("Hello TeamDocs");
            for (int index = 0; index < count; index++) {
                document.createParagraph().createRun().addPicture(input(image("png")), XWPFDocument.PICTURE_TYPE_PNG,
                        "image" + index + ".png", Units.toEMU(16), Units.toEMU(8));
            }
            document.write(output);
            return output.toByteArray();
        }
    }

    /** 创建只模拟描述输出的视觉服务。 */
    private ImageUnderstandingService model() {
        vision.setBaseUrl("http://unused.invalid/v1");
        vision.setApiKey("test-key");
        vision.setModelName("test-model");
        ImageUnderstandingService model = spy(new ImageUnderstandingService(vision, mock(RetrievalHttp.class)));
        doReturn("### 概要\n示意图\n\n### 数据/逻辑流\nA 到 B\n\n### 可辨识文本\nHello").when(model).describe(any(), anyString());
        return model;
    }

    /** 创建内存输入流。 */
    private static ByteArrayInputStream input(byte[] bytes) { return new ByteArrayInputStream(bytes); }

    /** 生成纯 Java PNG 或 JPEG。 */
    private static byte[] image(String format) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream output = new MemoryCacheImageOutputStream(bytes)) {
            ImageIO.write(new BufferedImage(16, 8, BufferedImage.TYPE_INT_RGB), format, output);
        }
        return bytes.toByteArray();
    }

    /** 生成纯文字、扫描或混合 PDF。 */
    private static byte[] pdf(int pages, boolean images, boolean text) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    if (images) content.drawImage(LosslessFactory.createFromImage(document, ImageIO.read(input(image("png")))), 20, 20, 160, 80);
                    if (text) {
                        content.beginText();
                        content.setFont(PDType1Font.HELVETICA, 12);
                        content.newLineAtOffset(30, 700);
                        content.showText("Hello TeamDocs");
                        content.endText();
                    }
                }
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    /** 生成含重复内嵌图及可选外部关系的 DOCX。 */
    private static byte[] docx(boolean pictures, boolean external) throws Exception {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("Hello TeamDocs");
            if (pictures) {
                document.createParagraph().createRun().addPicture(input(image("png")), XWPFDocument.PICTURE_TYPE_PNG, "diagram.png", Units.toEMU(16), Units.toEMU(8));
                document.createTable(1, 1).getRow(0).getCell(0).getParagraphs().get(0).createRun()
                        .addPicture(input(image("png")), XWPFDocument.PICTURE_TYPE_PNG, "diagram.png", Units.toEMU(16), Units.toEMU(8));
            }
            if (external) document.getPackagePart().addExternalRelationship("https://example.invalid/private.png", "http://schemas.openxmlformats.org/officeDocument/2006/relationships/image");
            document.write(output);
            return output.toByteArray();
        }
    }
}
