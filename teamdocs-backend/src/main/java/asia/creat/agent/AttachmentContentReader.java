package asia.creat.agent;

import asia.creat.config.ParseProperties;
import asia.creat.parse.DocumentTextExtractor;
import asia.creat.parse.ExtractedText;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFGroupShape;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipInputStream;

/** 对话附件只准备模型输入，复用正文提取组件，不分块入库或调用另一套视觉模型。 */
@Component
public class AttachmentContentReader {
    private static final int MAX_CHARS = 100_000;
    private static final int MAX_PAGES = 100;
    private static final int MAX_SCAN_PAGES = 8;
    private final DocumentTextExtractor textExtractor;

    public AttachmentContentReader() {
        ParseProperties limits = new ParseProperties();
        limits.setMaxBytes(ChatAttachmentService.MAX_BYTES);
        limits.setMaxChars(MAX_CHARS);
        textExtractor = new DocumentTextExtractor(limits);
    }

    public List<AttachmentMessage.Part> read(String name, String mime, byte[] bytes) throws IOException {
        long deadline = System.nanoTime() + 15_000_000_000L;
        String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (mime.startsWith("image/")) return List.of(part(name, mime, bytes));
        try {
            if (List.of("docx", "xlsx", "pptx").contains(ext)) checkArchive(bytes, deadline);
            if (ext.equals("pdf")) return pdf(name, bytes, deadline);
            if (ext.equals("xlsx")) return List.of(text(name, spreadsheet(bytes, deadline)));
            if (ext.equals("pptx")) return List.of(text(name, slides(bytes, deadline)));
            // CSV/TSV 与文本使用同一 UTF-8 有界提取入口。
            String filename = List.of("csv", "tsv").contains(ext) ? name + ".txt" : name;
            ExtractedText extracted = textExtractor.extractSource(filename, mime, bytes);
            if (extracted.isSkipped()) throw new IOException(extracted.getReason());
            StringBuilder content = new StringBuilder();
            for (var segment : extracted.getSegments()) append(content, segment.text(), deadline);
            check(deadline);
            return List.of(text(name, content.toString()));
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("附件格式损坏、加密或不受支持，请检查文件", error);
        }
    }

    private List<AttachmentMessage.Part> pdf(String name, byte[] bytes, long deadline) throws IOException {
        try (PDDocument document = PDDocument.load(bytes)) {
            if (document.isEncrypted()) throw new IOException("不支持加密 PDF");
            if (document.getNumberOfPages() > MAX_PAGES) throw new IOException("PDF 超过100页，请拆分后上传");
            List<AttachmentMessage.Part> result = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            PDFTextStripper stripper = new PDFTextStripper();
            PDFRenderer renderer = new PDFRenderer(document);
            int images = 0;
            for (int page = 0; page < document.getNumberOfPages(); page++) {
                check(deadline);
                stripper.setStartPage(page + 1);
                stripper.setEndPage(page + 1);
                String content = stripper.getText(document);
                if (!content.isBlank()) {
                    append(text, "\n[第 " + (page + 1) + " 页]\n" + content, deadline);
                } else {
                    if (++images > MAX_SCAN_PAGES) throw new IOException("扫描页超过8页，请拆分后上传");
                    var box = document.getPage(page).getCropBox();
                    float longest = Math.max(box.getWidth(), box.getHeight());
                    if (!Float.isFinite(longest) || longest <= 0) throw new IOException("PDF 页面尺寸无效");
                    float scale = Math.min(1.5f, 1600f / longest);
                    var image = renderer.renderImage(page, scale);
                    try (var output = new ByteArrayOutputStream()) {
                        ImageIO.write(image, "png", output);
                        if (output.size() > ChatAttachmentService.MAX_BYTES) throw new IOException("PDF 页面图片过大");
                        result.add(part(name + " · 第 " + (page + 1) + " 页", "image/png", output.toByteArray()));
                    } finally { image.flush(); }
                }
            }
            if (!text.isEmpty()) result.add(0, text(name, text.toString()));
            if (result.isEmpty()) throw new IOException("附件没有可读取内容");
            return result;
        }
    }

    private String spreadsheet(byte[] bytes, long deadline) throws Exception {
        StringBuilder text = new StringBuilder();
        try (var book = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            int cells = 0;
            for (var sheet : book) {
                append(text, "\n[工作表：" + sheet.getSheetName() + "]\n", deadline);
                for (var row : sheet) {
                    check(deadline);
                    for (var cell : row) {
                        if (++cells > 20_000) throw new IOException("表格超过20000个单元格，请缩小范围");
                        // 不创建 FormulaEvaluator，公式及外部引用不执行。
                        append(text, cell.getAddress() + ": " + formatter.formatCellValue(cell) + "\t", deadline);
                    }
                    append(text, "\n", deadline);
                }
            }
        }
        return text.toString();
    }

    private String slides(byte[] bytes, long deadline) throws Exception {
        StringBuilder text = new StringBuilder();
        try (var deck = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            if (deck.getSlides().size() > MAX_PAGES) throw new IOException("幻灯片超过100页，请拆分后上传");
            int page = 0;
            for (var slide : deck.getSlides()) {
                append(text, "\n[幻灯片 " + (++page) + "]\n", deadline);
                for (var shape : slide.getShapes()) {
                    slideShape(text, shape, deadline, 0);
                }
            }
        }
        return text.toString();
    }

    private void slideShape(StringBuilder text, XSLFShape shape, long deadline, int depth) throws IOException {
        check(deadline);
        if (depth > 20) throw new IOException("幻灯片嵌套过深");
        if (shape instanceof XSLFTextShape paragraph) {
            append(text, paragraph.getText() + "\n", deadline);
        } else if (shape instanceof XSLFTable table) {
            for (var row : table.getRows()) {
                for (var cell : row.getCells()) append(text, cell.getText() + "\t", deadline);
                append(text, "\n", deadline);
            }
        } else if (shape instanceof XSLFGroupShape group) {
            for (var child : group.getShapes()) slideShape(text, child, deadline, depth + 1);
        }
    }

    private void checkArchive(byte[] bytes, long deadline) throws IOException {
        long expanded = 0;
        int entries = 0;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            byte[] buffer = new byte[8192];
            while (zip.getNextEntry() != null) {
                if (++entries > 2000) throw new IOException("文档压缩条目过多");
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    check(deadline);
                    expanded += read;
                    if (expanded > 40L * 1024 * 1024) throw new IOException("文档解压后超过40MB");
                }
            }
        }
        if (entries == 0) throw new IOException("Office 文件格式无效或已加密");
    }

    private void append(StringBuilder target, String value, long deadline) throws IOException {
        check(deadline);
        if (target.length() + value.length() > MAX_CHARS) throw new IOException("附件文本超过100000字符，请拆分后上传");
        target.append(value);
    }
    private void check(long deadline) throws IOException {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline) throw new IOException("附件处理超时，请拆分后上传");
    }
    private AttachmentMessage.Part text(String name, String value) throws IOException {
        if (value.isBlank()) throw new IOException("附件没有可提取文本");
        return part(name, "text/plain", value.getBytes(StandardCharsets.UTF_8));
    }
    private AttachmentMessage.Part part(String name, String mime, byte[] bytes) {
        return new AttachmentMessage.Part(name, mime, Base64.getEncoder().encodeToString(bytes));
    }
}
