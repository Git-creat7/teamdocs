package asia.creat.teamdocsbackend.agent;

import asia.creat.common.exception.BusinessException;
import asia.creat.controller.DocumentImageController;
import asia.creat.parse.DocumentImageReader;
import asia.creat.security.LoginUser;
import asia.creat.service.DocumentImageService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocumentImageControllerTest {
    private final DocumentImageService service = mock(DocumentImageService.class);
    private final DocumentImageController controller = new DocumentImageController(service);
    private final LoginUser user = new LoginUser(7L, "test");

    /** 受权原图不缓存并声明真实图片 MIME。 */
    @Test
    void returnsPrivateImageBytesWithoutAStoredUrl() {
        byte[] bytes = {1, 2, 3};

        when(service.read(1L, 10L, 100L, 0, user)).thenReturn(new DocumentImageReader.Preview(bytes, "image/png"));

        var response = controller.image(1L, 10L, 100L, 0, user);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(MediaType.IMAGE_PNG, response.getHeaders().getContentType());
        assertEquals("private, no-store", response.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));
        assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
        assertArrayEquals(bytes, (byte[]) response.getBody());
    }

    /** 临时繁忙与真实来源失效具有不同 HTTP 状态和机器可读错误码。 */
    @Test
    void distinguishesBusyUnavailableAndChangedSources() {
        for (var sample : new Object[][]{
                {"图片预览繁忙，请稍后再试", 429, "IMAGE_BUSY"},
                {"图片不可读取或超过预览限制", 400, "IMAGE_UNAVAILABLE"},
                {"原图已更新或不可访问", 404, "SOURCE_CHANGED"}}) {
            doThrow(new BusinessException((String) sample[0])).when(service).read(any(), any(), any(), any(), any());

            var response = controller.image(1L, 10L, 100L, 0, user);

            assertEquals(sample[1], response.getStatusCode().value());

            Map<?, ?> body = (Map<?, ?>) response.getBody();

            assertEquals(sample[2], body.get("errorCode"));
            assertEquals(sample[0], body.get("msg"));
        }
    }
}
