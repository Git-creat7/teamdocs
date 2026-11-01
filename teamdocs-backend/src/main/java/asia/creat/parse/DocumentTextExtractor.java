package asia.creat.parse;

import asia.creat.config.ParseProperties;
import asia.creat.retrieval.RetrievalContext;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.Reader;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnmappableCharacterException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
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
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.microsoft.OfficeParser;
import org.apache.tika.parser.microsoft.OfficeParserConfig;
import org.apache.tika.parser.microsoft.ooxml.OOXMLParser;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
@Slf4j
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

        return extractSource(fileName, contentType, DocumentImageReader.readLimited(input, properties.getMaxBytes()));
    }

    /** 复用已读取的原件，不复制或修改数组；直接调用仍须校验字节上限。 */
    public ExtractedText extractSource(String fileName, String contentType, byte[] source) throws IOException {
        Kind kind = detect(fileName, contentType);

        if (kind == Kind.UNSUPPORTED) return ExtractedText.skipped("不支持解析该文件类型");

        if (properties.getMaxBytes() < 1) throw new IOException("字节上限配置无效");

        if (source.length > properties.getMaxBytes()) throw new IOException("内容超过字节上限");

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

            if (detect(null, actualType) != kind
                    && !actualType.equals("application/x-tika-ooxml-protected")) {
                throw new IOException("文件内容与 " + kind + " 类型不符");
            }

            if (actualType.equals("application/x-tika-ooxml-protected")) return ExtractedText.skipped("文件已加密");

            return kind == Kind.DOCX && visionEnabled() ? readDocxWithImages(source) : readOffice(stream, kind);
        }
    }

    public static boolean supported(String fileName, String contentType) {
        return detect(fileName, contentType) != Kind.UNSUPPORTED;
    }

    static Kind detect(String fileName, String contentType) {
        String ext = extension(fileName);

        switch (ext) {
            case "txt", "md", "markdown", "json", "jsonl", "csv", "tsv" -> { return Kind.TEXT; }

            case "pdf" -> { return Kind.PDF; }

            case "doc", "docx" -> { return ext.equals("doc") ? Kind.DOC : Kind.DOCX; }

            case "xls", "xlsx" -> { return ext.equals("xls") ? Kind.XLS : Kind.XLSX; }

            case "ppt", "pptx" -> { return ext.equals("ppt") ? Kind.PPT : Kind.PPTX; }

            case "png", "jpg", "jpeg", "webp" -> { return Kind.IMAGE; }
        }

        String type = contentType == null ? "" : contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);

        if (List.of("text/plain", "text/markdown", "text/csv", "text/tab-separated-values",
                "application/json", "application/x-ndjson").contains(type)) return Kind.TEXT;

        if ("application/pdf".equals(type)) return Kind.PDF;

        if ("application/msword".equals(type)) return Kind.DOC;

        if ("application/vnd.ms-excel".equals(type)) return Kind.XLS;

        if ("application/vnd.ms-powerpoint".equals(type)) return Kind.PPT;

        if ("application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(type)) return Kind.DOCX;

        if ("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet".equals(type)) return Kind.XLSX;

        if ("application/vnd.openxmlformats-officedocument.presentationml.presentation".equals(type)) return Kind.PPTX;

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

    private static final java.util.regex.Pattern PDF_PAGE_NUMBER = java.util.regex.Pattern.compile(
            "^(?i)(?:page\\s+\\d+(?:\\s+of\\s+\\d+)?|第\\s*\\d+\\s*页(?:\\s*/\\s*共\\s*\\d+\\s*页)?|\\d+\\s*/\\s*\\d+|[-–—]\\s*\\d+\\s*[-–—]|\\d{1,4})$"
    );

    private ExtractedText readPdf(InputStream input) throws IOException {
        try (PDDocument document = PDDocument.load(input)) {
            if (document.isEncrypted()) return ExtractedText.skipped("文件已加密");

            PDFTextStripper stripper = new PDFTextStripper();
            List<ExtractedText.Segment> segments = new ArrayList<>();
            long totalChars = 0;
            int numPages = document.getNumberOfPages();
            List<String> rawPageTexts = new ArrayList<>(numPages);

            for (int page = 1; page <= numPages; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                rawPageTexts.add(stripper.getText(document));
            }

            List<String> cleanedPageTexts = cleanPdfPages(rawPageTexts);

            for (int page = 1; page <= numPages; page++) {
                String text = TextChunker.normalize(cleanedPageTexts.get(page - 1));

                totalChars += text.length();

                if (totalChars > properties.getMaxChars()) return ExtractedText.skipped("正文超过解析长度上限");

                if (!text.isEmpty()) segments.add(new ExtractedText.Segment(page, text));

                if (visionEnabled()) {
                    boolean imagePage = text.isEmpty();

                    try {
                        imagePage |= DocumentImageReader.hasImages(document.getPage(page - 1), vision.limits());
                    } catch (IOException | RuntimeException imageFailure) {
                        RetrievalContext.check();
                        // 交给逐图阶段隔离失败，不能丢掉已经提取的页面文本。
                        imagePage = true;
                    }

                    if (imagePage) segments.add(new ExtractedText.Segment(page, "", "pdf:" + page, "PDF 第 " + page + " 页"));
                }
            }

            return describeImages(segments, ref -> DocumentImageReader.render(document,
                    Integer.parseInt(ref.substring(4)), vision.limits()));
        } catch (InvalidPasswordException e) {
            return ExtractedText.skipped("文件已加密");
        }
    }

    static List<String> cleanPdfPages(List<String> rawPages) {
        if (rawPages == null || rawPages.size() < 3) {
            return rawPages != null ? rawPages : List.of();
        }

        int numPages = rawPages.size();
        int threshold = (int) Math.ceil(numPages * 0.7);

        // 统计首行（页眉候选）
        java.util.Map<String, Integer> topFreq = new java.util.HashMap<>();
        for (String pageText : rawPages) {
            String firstLine = findFirstNonBlankLine(pageText);
            if (firstLine != null && isCleanCandidate(firstLine)) {
                topFreq.merge(firstLine, 1, Integer::sum);
            }
        }

        String commonHeader = null;
        for (var entry : topFreq.entrySet()) {
            if (entry.getValue() >= threshold) {
                commonHeader = entry.getKey();
                break;
            }
        }

        // 统计尾行（页脚/页码候选）
        java.util.Map<String, Integer> bottomFreq = new java.util.HashMap<>();
        for (String pageText : rawPages) {
            String lastLine = findLastNonBlankLine(pageText);
            if (lastLine != null && isCleanCandidate(lastLine)) {
                bottomFreq.merge(lastLine, 1, Integer::sum);
            }
        }

        String commonFooter = null;
        for (var entry : bottomFreq.entrySet()) {
            if (entry.getValue() >= threshold) {
                commonFooter = entry.getKey();
                break;
            }
        }

        List<String> cleaned = new ArrayList<>(numPages);
        for (String pageText : rawPages) {
            cleaned.add(cleanSinglePdfPage(pageText, commonHeader, commonFooter));
        }

        return cleaned;
    }

    private static boolean isCleanCandidate(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) return false;
        return !trimmed.startsWith("#") && !trimmed.startsWith("|") && !trimmed.startsWith(">");
    }

    private static String findFirstNonBlankLine(String text) {
        if (text == null) return null;
        String[] lines = text.split("\r?\n");
        for (int i = 0; i < Math.min(3, lines.length); i++) {
            String t = lines[i].trim();
            if (!t.isEmpty()) return t;
        }
        return null;
    }

    private static String findLastNonBlankLine(String text) {
        if (text == null) return null;
        String[] lines = text.split("\r?\n");
        for (int i = lines.length - 1; i >= Math.max(0, lines.length - 3); i--) {
            String t = lines[i].trim();
            if (!t.isEmpty()) return t;
        }
        return null;
    }

    private static String cleanSinglePdfPage(String text, String commonHeader, String commonFooter) {
        if (text == null || text.isEmpty()) return text;
        String[] lines = text.split("\r?\n");
        int start = 0;
        int end = lines.length;

        while (start < end && lines[start].trim().isEmpty()) {
            start++;
        }
        if (start < end && commonHeader != null && lines[start].trim().equals(commonHeader)) {
            start++;
        }

        while (end > start && lines[end - 1].trim().isEmpty()) {
            end--;
        }
        if (end > start) {
            String last = lines[end - 1].trim();
            if ((commonFooter != null && last.equals(commonFooter)) || PDF_PAGE_NUMBER.matcher(last).matches()) {
                end--;
            }
        }

        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++) {
            sb.append(lines[i]).append("\n");
        }
        return sb.toString();
    }

    /** 新旧 Office 复用有界正文提取，不执行宏或重新计算公式。 */
    private ExtractedText readOffice(InputStream input, Kind kind) throws IOException {
        try {
            BodyContentHandler handler = new BodyContentHandler(properties.getMaxChars());

            Parser parser = switch (kind) {
                case DOC, XLS, PPT -> new OfficeParser();
                default -> new OOXMLParser();
            };

            ParseContext context = new ParseContext();
            OfficeParserConfig officeParserConfig = new OfficeParserConfig();
            officeParserConfig.setIncludeHeadersAndFooters(false);
            context.set(OfficeParserConfig.class, officeParserConfig);

            parser.parse(input, handler, new Metadata(), context);

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

            throw new IOException(kind + " 解析失败", e);
        }
    }

    /** 保留 Tika 正文，并按文档位置收集图片。 */
    private ExtractedText readDocxWithImages(byte[] source) throws IOException {
        try (var pack = DocumentImageReader.openDocx(source); XWPFDocument document = new XWPFDocument(pack)) {
            ExtractedText text = readOffice(new ByteArrayInputStream(source), Kind.DOCX);

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

                        segments.add(new ExtractedText.Segment(null, "", data == null ? null
                                        : "docx:" + data.getPackagePart().getPartName().getName(),
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

    /** 只尝试前 N 幅图片，单图失败不丢弃正文或已成功的图像描述。 */
    private ExtractedText describeImages(List<ExtractedText.Segment> segments, ImageLoader loader) throws IOException {
        long chars = segments.stream().filter(segment -> !imageCandidate(segment)).mapToLong(segment -> segment.text().length()).sum();

        if (chars > properties.getMaxChars()) return ExtractedText.skipped("正文超过解析长度上限");

        int limit = visionEnabled() ? Math.max(0, vision.limits().getMaxImages()) : 0;
        int attempted = 0, failed = 0, limited = 0, oversized = 0;
        List<ExtractedText.Segment> result = new ArrayList<>();

        for (var segment : segments) {
            if (!imageCandidate(segment)) {
                if (!segment.text().isBlank()) result.add(segment);

                continue;
            }

            if (attempted >= limit) {
                limited++;

                continue;
            }

            attempted++;
            RetrievalContext.check();

            try {
                if (segment.imageRef() == null) throw new IOException("DOCX 内嵌图片关系无效");

                var image = loader.read(segment.imageRef());
                String description = "图像描述（模型生成） · " + segment.imageLabel() + "\n\n"
                        + vision.describe(image.content(), image.contentType());

                if (chars + description.length() > properties.getMaxChars()) {
                    oversized++;

                    continue;
                }

                chars += description.length();
                result.add(new ExtractedText.Segment(segment.pageNumber(), description, segment.imageRef(), segment.imageLabel()));
            } catch (IOException | RuntimeException error) {
                // 运行已失效时不能降级后继续发图；普通单图错误则隔离。
                RetrievalContext.check();

                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("图片解析已中断");

                failed++;
                log.warn("跳过失败图片: exceptionType={}", error.getClass().getSimpleName());
            }
        }

        if (failed + limited + oversized > 0) {
            log.warn("图片部分解析: attempted={}, failed={}, countLimited={}, charLimited={}", attempted, failed, limited, oversized);
        }

        if (result.isEmpty()) {
            return ExtractedText.skipped(attempted + limited > 0 ? "没有可用正文，图片理解失败或受限" : "没有可提取文本");
        }

        if (failed + limited + oversized > 0) {
            String notice = "图像解析说明：" + failed + " 幅图片解析失败，" + limited
                    + " 幅超出数量上限，" + oversized + " 幅描述超出正文预算；这些图片内容未纳入检索。";

            if (chars + notice.length() <= properties.getMaxChars()) result.add(new ExtractedText.Segment(null, notice));
        }

        return ExtractedText.of(result);
    }

    /** 关系损坏的图片仍以位置标记识别，不能被当作正常正文。 */
    private static boolean imageCandidate(ExtractedText.Segment segment) {
        return segment.imageRef() != null || segment.imageLabel() != null;
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

    enum Kind { TEXT, PDF, DOC, DOCX, XLS, XLSX, PPT, PPTX, IMAGE, UNSUPPORTED }
}
