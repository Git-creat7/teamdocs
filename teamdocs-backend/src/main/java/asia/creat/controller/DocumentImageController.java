package asia.creat.controller;

import asia.creat.parse.DocumentImageReader;
import asia.creat.security.LoginUser;
import asia.creat.service.DocumentImageService;
import asia.creat.common.exception.BusinessException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/spaces/{spaceId}/documents")
public class DocumentImageController {
    private final DocumentImageService images;

    /**
     * 经身份、空间与来源校验后返回原图，禁止浏览器缓存私有图片。
     * @param spaceId 空间ID
     * @param documentId 文档ID
     * @param chunkId 分块ID
     * @param parseVersion 解析版本
     * @param user 当前用户
     * @return 图片响应
     */
    @GetMapping("/{documentId}/chunks/{chunkId}/image")
    public ResponseEntity<?> image(@PathVariable Long spaceId, @PathVariable Long documentId,
                                        @PathVariable Long chunkId, @RequestParam Integer parseVersion,
                                        @AuthenticationPrincipal LoginUser user) {
        try {
            DocumentImageReader.Preview image = images.read(spaceId, documentId, chunkId, parseVersion, user);
            return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.contentType()))
                    .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                    .header("X-Content-Type-Options", "nosniff")
                    .body(image.content());
        } catch (BusinessException e) {
            boolean busy = "图片预览繁忙，请稍后再试".equals(e.getMessage());
            boolean unavailable = "图片不可读取或超过预览限制".equals(e.getMessage());
            return ResponseEntity.status(busy ? 429 : unavailable ? 400 : 404)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                    .body(Map.of("code", 0, "msg", e.getMessage(),
                            "errorCode", busy ? "IMAGE_BUSY" : unavailable ? "IMAGE_UNAVAILABLE" : "SOURCE_CHANGED"));
        }
    }
}
