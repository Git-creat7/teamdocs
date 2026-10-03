package asia.creat.parse;

import asia.creat.config.ParseProperties;
import asia.creat.config.VisionProperties;
import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.PackagingURIHelper;
import org.apache.poi.openxml4j.opc.TargetMode;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class DocumentImageReader {
    private final ParseProperties parse;
    private final VisionProperties vision;

    /** 绑定原件与图像读取限额。 */
    public DocumentImageReader(ParseProperties parse, VisionProperties vision) {
        this.parse = parse;
        this.vision = vision;
        ZipSecureFile.setMinInflateRatio(0.01);
    }

    /** 从原件读取数据库中已授权的图片引用。 */
    public Preview read(String fileName, String contentType, InputStream input, String imageRef) throws IOException {
        byte[] source = readLimited(input, parse.getMaxBytes());
        // imageRef 来自已授权的分块；显示名可改动，不能作为原件格式依据。
        if ("original".equals(imageRef)) {
            return prepare(source, null, vision, false);
        }
        if (imageRef != null && imageRef.matches("pdf:[1-9][0-9]{0,8}")) {
            try (PDDocument document = PDDocument.load(source)) {
                if (document.isEncrypted()) throw new IOException("文件已加密");
                return render(document, Integer.parseInt(imageRef.substring(4)), vision);
            }
        }
        if (validPartRef(imageRef)) {
            try (OPCPackage document = openDocx(source)) {
                return readPart(document, imageRef, vision);
            }
        }
        throw new IOException("图片引用与原件类型不符");
    }

    public record Preview(byte[] content, String contentType) {
    }

    /** 在内存中读取限定字节数。 */
    static byte[] readLimited(InputStream input, long maxBytes) throws IOException {
        if (maxBytes < 1) throw new IOException("字节上限配置无效");
        long limit = Math.min(maxBytes, Integer.MAX_VALUE - 1L);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer, 0, (int) Math.min(buffer.length, limit - out.size() + 1))) != -1) {
            if (out.size() + (long) count > limit) throw new IOException("内容超过字节上限");
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }

    /** 先检查真实格式和像素，再解码并按需缩放。 */
    static Preview prepare(byte[] image, String mime, VisionProperties limits, boolean resize) throws IOException {
        if (image.length == 0 || image.length > limits.getMaxImageBytes()) throw new IOException("图片超过字节上限或为空");
        try (MemoryCacheImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(image))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("图片格式不受支持");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                String actual = "image/" + (format.equals("jpg") ? "jpeg" : format);
                if (!Set.of("image/png", "image/jpeg", "image/webp").contains(actual)
                        || (mime != null && !actual.equals(mime))) {
                    throw new IOException("图片真实格式不符或不受支持");
                }
                checkPixels(reader.getWidth(0), reader.getHeight(0), limits);
                BufferedImage decoded = reader.read(0);
                if (decoded == null) throw new IOException("图片解码失败");
                if (!resize) return new Preview(image, actual);
                double scale = Math.min(1, limits.getMaxDimension() / (double) Math.max(decoded.getWidth(), decoded.getHeight()));
                if (scale <= 0) throw new IOException("图片尺寸上限配置无效");
                BufferedImage scaled = new BufferedImage(Math.max(1, (int) (decoded.getWidth() * scale)),
                        Math.max(1, (int) (decoded.getHeight() * scale)), BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = scaled.createGraphics();
                try {
                    graphics.setColor(Color.WHITE);
                    graphics.fillRect(0, 0, scaled.getWidth(), scaled.getHeight());
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    graphics.drawImage(decoded, 0, 0, scaled.getWidth(), scaled.getHeight(), null);
                } finally {
                    graphics.dispose();
                }
                return encode(scaled, limits);
            } finally {
                reader.dispose();
            }
        }
    }

    /** 限制解码前的像素数量。 */
    private static void checkPixels(int width, int height, VisionProperties limits) throws IOException {
        if (width < 1 || height < 1 || (long) width * height > limits.getMaxPixels()) throw new IOException("图片超过像素上限");
    }

    /** 编码有界的 PNG，必要时使用 JPEG。 */
    private static Preview encode(BufferedImage image, VisionProperties limits) throws IOException {
        for (String format : List.of("png", "jpeg")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (MemoryCacheImageOutputStream output = new MemoryCacheImageOutputStream(out)) {
                if (!ImageIO.write(image, format, output)) continue;
            }
            if (out.size() <= limits.getMaxImageBytes()) return new Preview(out.toByteArray(), "image/" + format);
        }
        throw new IOException("渲染图片超过字节上限");
    }

    /** 用不超过 144 DPI 的比例渲染一页。 */
    static Preview render(PDDocument document, int pageNumber, VisionProperties limits) throws IOException {
        if (pageNumber < 1 || pageNumber > document.getNumberOfPages()) throw new IOException("PDF 页码无效");
        PDPage page = document.getPage(pageNumber - 1);
        hasImages(page, limits);
        double width = page.getCropBox().getWidth(), height = page.getCropBox().getHeight();
        if (!Double.isFinite(width + height) || width <= 0 || height <= 0 || limits.getMaxDimension() < 1 || limits.getMaxPixels() < 1) {
            throw new IOException("PDF 页面尺寸无效");
        }
        double scale = Math.min(2, Math.min(limits.getMaxDimension() / Math.max(width, height),
                Math.sqrt(limits.getMaxPixels() / (width * height))));
        PDFRenderer renderer = new PDFRenderer(document);
        renderer.setSubsamplingAllowed(true);
        return encode(renderer.renderImage(pageNumber - 1, (float) scale, ImageType.RGB), limits);
    }

    /** 检查页面实际绘制的图片及其像素上限。 */
    static boolean hasImages(PDPage page, VisionProperties limits) throws IOException {
        ImageScan scan = new ImageScan(limits);
        scan.processPage(page);
        return scan.found;
    }

    private static final class ImageScan extends PDFStreamEngine {
        private final VisionProperties limits;
        private final Set<COSBase> visited = new HashSet<>();
        private boolean found;

        /** 记录图像限额。 */
        private ImageScan(VisionProperties limits) { this.limits = limits; }

        @Override
        protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
            PDImage image = null;
            if ("BI".equals(operator.getName())) {
                var parameters = operator.getImageParameters();
                if (parameters == null) throw new IOException("PDF 内联图片参数无效");
                checkPixels(parameters.getInt(COSName.WIDTH, parameters.getInt(COSName.W, 0)),
                        parameters.getInt(COSName.HEIGHT, parameters.getInt(COSName.H, 0)), limits);
                // PDInlineImage 构造时就会解压，预扫描只读取尺寸，不能先解码。
                found = true;
                return;
            } else if ("Do".equals(operator.getName()) && !operands.isEmpty() && operands.get(0) instanceof COSName name) {
                PDXObject object = getResources().getXObject(name);
                if (object instanceof PDFormXObject form && visited.add(form.getCOSObject())) {
                    if (visited.size() > 64) throw new IOException("PDF 图片嵌套资源过多");
                    showForm(form);
                } else if (object instanceof PDImageXObject raster) {
                    image = raster;
                    if (raster.getSoftMask() != null) checkPixels(raster.getSoftMask().getWidth(), raster.getSoftMask().getHeight(), limits);
                    if (raster.getMask() != null) checkPixels(raster.getMask().getWidth(), raster.getMask().getHeight(), limits);
                }
            }
            if (image != null) {
                checkPixels(image.getWidth(), image.getHeight(), limits);
                found = true;
            }
        }
    }

    /** 仅接受固定媒体目录内的部件引用。 */
    private static boolean validPartRef(String ref) {
        return ref != null && ref.matches("docx:/word/media/[A-Za-z0-9][A-Za-z0-9_.-]*");
    }

    /** 打开内存中的 DOCX 并拒绝外部图片关系。 */
    static OPCPackage openDocx(byte[] source) throws IOException {
        OPCPackage document = null;
        try {
            document = OPCPackage.open(new ByteArrayInputStream(source));
            for (PackagePart part : document.getParts()) {
                if (part.isRelationshipPart()) continue;
                for (var relation : part.getRelationships()) {
                    if (relation.getRelationshipType().endsWith("/image") && relation.getTargetMode() == TargetMode.EXTERNAL) {
                        throw new IOException("DOCX 不允许外部图片关系");
                    }
                }
            }
            return document;
        } catch (InvalidFormatException | IOException | RuntimeException e) {
            if (document != null) document.revert();
            throw new IOException("DOCX 图片包无效或包含外部图片关系");
        }
    }

    /** 只读取指定内嵌部件并保留原图。 */
    static Preview readPart(OPCPackage document, String ref, VisionProperties limits) throws IOException {
        if (!validPartRef(ref)) throw new IOException("DOCX 图片引用无效");
        try {
            PackagePart part = document.getPart(PackagingURIHelper.createPartName(ref.substring(5)));
            if (part == null) throw new IOException("DOCX 图片不存在");
            try (InputStream input = part.getInputStream()) {
                return prepare(readLimited(input, limits.getMaxImageBytes()), part.getContentType(), limits, false);
            }
        } catch (InvalidFormatException e) {
            throw new IOException("DOCX 图片引用无效");
        }
    }
}
