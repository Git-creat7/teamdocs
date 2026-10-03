package asia.creat.parse;

import asia.creat.config.ParseProperties;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.tika.detect.DefaultDetector;
import org.apache.tika.exception.EncryptedDocumentException;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.microsoft.ooxml.OOXMLParser;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnmappableCharacterException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class DocumentTextExtractor {
    private final ParseProperties properties;
    private final ImageUnderstandingService vision;

    /** 保留不启用视觉服务的构造方式。 */
    public DocumentTextExtractor(ParseProperties properties) {
        this(properties, (ImageUnderstandingService) null);
    }

    /** 通过可选依赖接入视觉服务。 */
    @Autowired
    public DocumentTextExtractor(ParseProperties properties, ObjectProvider<ImageUnderstandingService> vision) {
        this(properties, vision.getIfAvailable());
    }

    /** 显式绑定视觉服务，便于独立测试。 */
    public DocumentTextExtractor(ParseProperties properties, ImageUnderstandingService vision) {
        this.properties = properties;
        this.vision = vision;
        ZipSecureFile.setMinInflateRatio(0.01);
    }

    public ExtractedText extract(String fileName, String contentType, InputStream input) throws IOException {
        Kind kind = detect(fileName, contentType);
        if (kind == Kind.UNSUPPORTED) return ExtractedText.skipped("不支持解析该文件类型");
        byte[] source = DocumentImageReader.readLimited(input, properties.getMaxBytes());
        try (TikaInputStream stream = TikaInputStream.get(new ByteArrayInputStream(source))) {
            String actualType = new DefaultDetector().detect(stream, new Metadata()).toString();
            if (kind == Kind.IMAGE) {
                if (!visionEnabled()) return ExtractedText.skipped("未配置图像理解服务");
                if (!actualType.equals(imageMime(fileName, contentType))) {
                    throw new IOException("图片真实格式与声明类型不符");
                }
                return describeImages(List.of(new ExtractedText.Segment(null, "", "original", "原图")),
                        ref -> DocumentImageReader.prepare(source, actualType, vision.limits(), false));
            }
            if (kind == Kind.TEXT) {
                if (actualType.equals("application/pdf") || actualType.contains("zip")
                        || actualType.contains("officedocument") || actualType.startsWith("image/")) {
                    throw new IOException("文件内容与文本类型不符");
                }
                return readPlain(stream);
            }
            if (kind == Kind.PDF) {
                if (!actualType.equals("application/pdf")) throw new IOException("文件内容与 PDF 类型不符");
                return readPdf(stream);
            }
            if (!actualType.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                    && !actualType.equals("application/x-tika-ooxml-protected")) {
                throw new IOException("文件内容与 DOCX 类型不符");
            }
            if (actualType.equals("application/x-tika-ooxml-protected")) return ExtractedText.skipped("文件已加密");
            return visionEnabled() ? readDocxWithImages(source) : readDocx(stream);
        }
    }

    public static boolean supported(String fileName, String contentType) {
        return detect(fileName, contentType) != Kind.UNSUPPORTED;
    }

    static Kind detect(String fileName, String contentType) {
        String ext = extension(fileName);
        switch (ext) {
            case "txt", "md", "markdown" -> { return Kind.TEXT; }
            case "pdf" -> { return Kind.PDF; }
            case "docx" -> { return Kind.DOCX; }
            case "png", "jpg", "jpeg", "webp" -> { return Kind.IMAGE; }
        }
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (type.startsWith("text/plain") || type.startsWith("text/markdown")) return Kind.TEXT;
        if ("application/pdf".equals(type)) return Kind.PDF;
        if ("application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(type)) return Kind.DOCX;
        if (List.of("image/png", "image/jpeg", "image/webp").contains(type)) return Kind.IMAGE;
        return Kind.UNSUPPORTED;
    }

    /** 取得独立图片应有的真实 MIME。 */
    static String imageMime(String fileName, String contentType) {
        return switch (extension(fileName)) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            default -> contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        };
    }

    /** 检查可选视觉服务是否可用。 */
    private boolean visionEnabled() { return vision != null && vision.enabled(); }

    private ExtractedText readPlain(InputStream input) throws IOException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try (Reader reader = new InputStreamReader(input, decoder)) {
            StringBuilder text = new StringBuilder();
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                if (text.length() + read > properties.getMaxChars()) {
                    return ExtractedText.skipped("正文超过解析长度上限");
                }
                text.append(buffer, 0, read);
            }
            String normalized = TextChunker.normalize(text.toString());
            if (normalized.isEmpty()) {
                return ExtractedText.skipped("没有可提取文本");
            }
            return ExtractedText.of(List.of(new ExtractedText.Segment(null, normalized)));
        } catch (MalformedInputException | UnmappableCharacterException e) {
            throw new IOException("不是有效的 UTF-8 文本", e);
        }
    }

    private ExtractedText readPdf(InputStream input) throws IOException {
        try (PDDocument document = PDDocument.load(input)) {
            if (document.isEncrypted()) return ExtractedText.skipped("文件已加密");
            PDFTextStripper stripper = new PDFTextStripper();
            List<ExtractedText.Segment> segments = new ArrayList<>();
            long totalChars = 0;
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = TextChunker.normalize(stripper.getText(document));
                totalChars += text.length();
                if (totalChars > properties.getMaxChars()) return ExtractedText.skipped("正文超过解析长度上限");
                if (!text.isEmpty()) segments.add(new ExtractedText.Segment(page, text));
                if (visionEnabled() && (DocumentImageReader.hasImages(document.getPage(page - 1), vision.limits()) || text.isEmpty())) {
                    segments.add(new ExtractedText.Segment(page, "", "pdf:" + page, "PDF 第 " + page + " 页"));
                }
            }
            return describeImages(segments, ref -> DocumentImageReader.render(document,
                    Integer.parseInt(ref.substring(4)), vision.limits()));
        } catch (InvalidPasswordException e) {
            return ExtractedText.skipped("文件已加密");
        }
    }

    private ExtractedText readDocx(InputStream input) throws IOException {
        try {
            BodyContentHandler handler = new BodyContentHandler(properties.getMaxChars());
            new OOXMLParser().parse(input, handler, new Metadata(), new ParseContext());
            String text = TextChunker.normalize(handler.toString());
            if (text.isEmpty()) {
                return ExtractedText.skipped("没有可提取文本");
            }
            return ExtractedText.of(List.of(new ExtractedText.Segment(null, text)));
        } catch (Exception e) {
            if (causedBy(e, WriteLimitReachedException.class)) {
                return ExtractedText.skipped("正文超过解析长度上限");
            }
            if (causedBy(e, EncryptedDocumentException.class)) {
                return ExtractedText.skipped("文件已加密");
            }
            if (e instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("DOCX 解析失败", e);
        }
    }

    /** 保留 Tika 正文，并按文档位置收集图片。 */
    private ExtractedText readDocxWithImages(byte[] source) throws IOException {
        try (var pack = DocumentImageReader.openDocx(source); XWPFDocument document = new XWPFDocument(pack)) {
            ExtractedText text = readDocx(new ByteArrayInputStream(source));
            if (text.isSkipped() && !"没有可提取文本".equals(text.getReason())) return text;
            List<ExtractedText.Segment> segments = new ArrayList<>(text.getSegments());
            collectPictures(document.getBodyElements(), "DOCX 正文", segments);
            for (int i = 0; i < document.getHeaderList().size(); i++) {
                collectPictures(document.getHeaderList().get(i).getBodyElements(), "DOCX 页眉 " + (i + 1), segments);
            }
            for (int i = 0; i < document.getFooterList().size(); i++) {
                collectPictures(document.getFooterList().get(i).getBodyElements(), "DOCX 页脚 " + (i + 1), segments);
            }
            return describeImages(segments, ref -> DocumentImageReader.readPart(pack, ref, vision.limits()));
        }
    }

    /** 遍历段落、文本运行和嵌套表格中的每次图片出现。 */
    private void collectPictures(List<IBodyElement> elements, String position, List<ExtractedText.Segment> segments) throws IOException {
        for (int i = 0; i < elements.size(); i++) {
            IBodyElement element = elements.get(i);
            String location = position + " / " + (i + 1);
            if (element instanceof XWPFParagraph paragraph) {
                for (int run = 0; run < paragraph.getRuns().size(); run++) {
                    List<XWPFPicture> pictures = paragraph.getRuns().get(run).getEmbeddedPictures();
                    for (int image = 0; image < pictures.size(); image++) {
                        XWPFPictureData data = pictures.get(image).getPictureData();
                        if (data == null) throw new IOException("DOCX 内嵌图片关系无效");
                        segments.add(new ExtractedText.Segment(null, "", "docx:" + data.getPackagePart().getPartName().getName(),
                                location + " 段落 / run " + (run + 1) + " / 图 " + (image + 1)));
                    }
                }
            } else if (element instanceof XWPFTable table) {
                for (int row = 0; row < table.getRows().size(); row++) {
                    List<XWPFTableCell> cells = table.getRows().get(row).getTableCells();
                    for (int cell = 0; cell < cells.size(); cell++) {
                        collectPictures(cells.get(cell).getBodyElements(), location + " 表格 / 行 " + (row + 1) + " / 列 " + (cell + 1), segments);
                    }
                }
            }
        }
    }

    /** 先检查数量、正文和全部图像，再逐幅调用模型。 */
    private ExtractedText describeImages(List<ExtractedText.Segment> segments, ImageLoader loader) throws IOException {
        long count = segments.stream().filter(segment -> segment.imageRef() != null).count();
        if (count > 0 && count > vision.limits().getMaxImages()) return ExtractedText.skipped("图片数量超过图像理解上限");
        long chars = segments.stream().mapToLong(segment -> segment.text().length()).sum();
        if (chars > properties.getMaxChars()) return ExtractedText.skipped("正文超过解析长度上限");
        List<DocumentImageReader.Preview> images = new ArrayList<>();
        for (var segment : segments) {
            if (segment.imageRef() != null) images.add(loader.read(segment.imageRef()));
        }
        List<ExtractedText.Segment> result = new ArrayList<>();
        int index = 0;
        for (var segment : segments) {
            if (segment.imageRef() == null) { result.add(segment); continue; }
            var image = images.get(index++);
            String description = "图像描述（模型生成） · " + segment.imageLabel() + "\n\n" + vision.describe(image.content(), image.contentType());
            chars += description.length();
            if (chars > properties.getMaxChars()) return ExtractedText.skipped("正文超过解析长度上限");
            result.add(new ExtractedText.Segment(segment.pageNumber(), description, segment.imageRef(), segment.imageLabel()));
        }
        return result.isEmpty() ? ExtractedText.skipped("没有可提取文本") : ExtractedText.of(result);
    }

    private interface ImageLoader {
        /** 读取一幅已定位的原件图片。 */
        DocumentImageReader.Preview read(String ref) throws IOException;
    }

    private static boolean causedBy(Throwable error, Class<? extends Throwable> type) {
        Throwable current = error;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String extension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    enum Kind { TEXT, PDF, DOCX, IMAGE, UNSUPPORTED }
}
