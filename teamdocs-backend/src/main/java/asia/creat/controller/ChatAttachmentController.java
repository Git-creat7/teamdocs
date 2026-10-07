package asia.creat.controller;

import asia.creat.agent.ChatAttachmentService;
import asia.creat.common.Result;
import asia.creat.security.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;

@RestController
@RequestMapping("/spaces/{spaceId}/agent")
@RequiredArgsConstructor
public class ChatAttachmentController {
    private final ChatAttachmentService attachments;
    @PostMapping("/attachments")
    public Result upload(@PathVariable Long spaceId, @RequestParam MultipartFile file, @AuthenticationPrincipal LoginUser user) throws IOException {
        return Result.success(attachments.upload(spaceId, file, user));
    }
    @GetMapping("/runs/{runId}/attachments")
    public Result list(@PathVariable Long spaceId, @PathVariable Long runId, @AuthenticationPrincipal LoginUser user) {
        return Result.success(attachments.list(spaceId, runId, user));
    }
    @GetMapping("/attachments/{id}")
    public ResponseEntity<byte[]> read(@PathVariable Long spaceId, @PathVariable String id, @AuthenticationPrincipal LoginUser user) throws IOException {
        return ResponseEntity.ok().header("Cache-Control", "private, no-store")
                .header("X-Content-Type-Options", "nosniff").header("Content-Disposition", "attachment")
                .header("Content-Type", "application/octet-stream").body(attachments.read(spaceId, id, user));
    }
    @DeleteMapping("/attachments/{id}")
    public Result remove(@PathVariable Long spaceId, @PathVariable String id, @AuthenticationPrincipal LoginUser user) {
        attachments.remove(spaceId, id, user); return Result.success();
    }
}
