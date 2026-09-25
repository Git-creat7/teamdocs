package asia.creat.parse;

import asia.creat.config.ParseProperties;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.tika.detect.DefaultDetector;
import org.apache.tika.exception.EncryptedDocumentException;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.microsoft.ooxml.OOXMLParser;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;

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

    public DocumentTextExtractor(ParseProperties properties) {
        this.properties = properties;
        ZipSecureFile.setMinInflateRatio(0.01);
    }

    public ExtractedText extract(String fileName, String contentType, InputStream input) throws IOException {
        Kind kind = detect(fileName, contentType);
        if (kind == Kind.UNSUPPORTED) {
            return ExtractedText.skipped("不支持解析该文件类型");
        }
        try (TikaInputStream stream = TikaInputStream.get(input)) {
            String actualType = new DefaultDetector().detect(stream, new Metadata()).toString();
            if (kind == Kind.TEXT) {
                if (actualType.equals("application/pdf") || actualType.contains("zip")
                        || actualType.contains("officedocument") || actualType.startsWith("image/")) {
                    throw new IOException("文件内容与文本类型不符");
                }
                return readPlain(stream);
            }
            if (kind == Kind.PDF) {
                if (!actualType.equals("application/pdf")) {
                    throw new IOException("文件内容与 PDF 类型不符");
                }
                return readPdf(stream);
            }
            if (!actualType.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                    && !actualType.equals("application/x-tika-ooxml-protected")) {
                throw new IOException("文件内容与 DOCX 类型不符");
            }
            return readDocx(stream);
        }
    }

    public static boolean supported(String fileName, String contentType) {
        return detect(fileName, contentType) != Kind.UNSUPPORTED;
    }

    static Kind detect(String fileName, String contentType) {
        String ext = extension(fileName);
        switch (ext) {
            case "txt", "md", "markdown" -> {
                return Kind.TEXT;
            }
            case "pdf" -> {
                return Kind.PDF;
            }
            case "docx" -> {
                return Kind.DOCX;
            }
        }
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (type.startsWith("text/plain") || type.startsWith("text/markdown")) {
            return Kind.TEXT;
        }
        if ("application/pdf".equals(type)) {
            return Kind.PDF;
        }
        if ("application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(type)) {
            return Kind.DOCX;
        }
        return Kind.UNSUPPORTED;
    }

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
            if (document.isEncrypted()) {
                return ExtractedText.skipped("文件已加密");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            List<ExtractedText.Segment> segments = new ArrayList<>();
            int totalChars = 0;
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = TextChunker.normalize(stripper.getText(document));
                if (text.isEmpty()) {
                    continue;
                }
                totalChars += text.length();
                if (totalChars > properties.getMaxChars()) {
                    return ExtractedText.skipped("正文超过解析长度上限");
                }
                segments.add(new ExtractedText.Segment(page, text));
            }
            if (segments.isEmpty()) {
                return ExtractedText.skipped("没有可提取文本");
            }
            return ExtractedText.of(segments);
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

    enum Kind {
        TEXT, PDF, DOCX, UNSUPPORTED
    }
}
