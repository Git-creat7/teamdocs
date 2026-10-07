package asia.creat.agent;

import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class ChatAttachmentValidationTest {
    @Test
    void verifiesImagesAndFilesWithoutParsingKnowledgeBaseContent() throws Exception {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
        assertEquals("image/png", ChatAttachmentService.validate("photo.png", bytes.toByteArray()));
        assertThrows(RuntimeException.class, () -> ChatAttachmentService.validate("photo.jpg", bytes.toByteArray()));
        assertEquals("application/json", ChatAttachmentService.validate("data.json", "{\"key\":1}".getBytes(StandardCharsets.UTF_8)));
        assertEquals("application/pdf", ChatAttachmentService.validate("file.pdf", "%PDF-1.7".getBytes(StandardCharsets.US_ASCII)));
        assertThrows(RuntimeException.class, () -> ChatAttachmentService.validate("file.pdf", "not pdf".getBytes(StandardCharsets.UTF_8)));
        assertThrows(Exception.class, () -> ChatAttachmentService.validate("file.txt", new byte[]{(byte)0xff}));
        assertThrows(RuntimeException.class, () -> ChatAttachmentService.validate("run.exe", new byte[]{1}));
    }
}
