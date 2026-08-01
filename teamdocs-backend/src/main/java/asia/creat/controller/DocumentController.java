package asia.creat.controller;

import asia.creat.common.Result;
import asia.creat.dto.MoveDocumentDTO;
import asia.creat.dto.PageQuery;
import asia.creat.dto.RenameDocumentDTO;
import asia.creat.dto.RestoreDocumentDTO;
import asia.creat.security.LoginUser;
import asia.creat.service.DocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/spaces/{spaceId}/documents")
@RequiredArgsConstructor
public class DocumentController {
    private final DocumentService documentService;

    @PostMapping("/upload")
    public Result uploadDocument(@PathVariable Long spaceId,
                                 @RequestParam(defaultValue = "0") Long folderId,
                                 @RequestParam MultipartFile file,
                                 @AuthenticationPrincipal LoginUser loginUser) {
        documentService.upload(spaceId, folderId, file, loginUser);
        return Result.success();
    }

    @GetMapping("")
    public Result listByFolder(@PathVariable Long spaceId,
                               @RequestParam(defaultValue = "0") Long folderId,
                               @Validated @ModelAttribute PageQuery pageQuery,
                               @AuthenticationPrincipal LoginUser loginUser) {
        return Result.success(documentService.listByFolder(spaceId, folderId, pageQuery, loginUser));
    }

    @DeleteMapping("/{documentId}")
    public Result deleteDocument(@PathVariable Long spaceId,
                                 @PathVariable Long documentId,
                                 @AuthenticationPrincipal LoginUser loginUser) {
        documentService.deleteDocument(spaceId, documentId, loginUser);
        return Result.success();
    }

    @PutMapping("/{documentId}/rename")
    public Result renameDocument(@PathVariable Long spaceId,
                                 @PathVariable Long documentId,
                                 @RequestBody @Validated RenameDocumentDTO dto,
                                 @AuthenticationPrincipal LoginUser loginUser) {
        documentService.renameDocument(spaceId, documentId, dto, loginUser);
        return Result.success();
    }

    @PutMapping("/{documentId}/move")
    public Result moveDocument(@PathVariable Long spaceId,
                               @PathVariable Long documentId,
                               @RequestBody @Validated MoveDocumentDTO dto,
                               @AuthenticationPrincipal LoginUser loginUser) {
        documentService.moveDocument(spaceId, documentId, dto, loginUser);
        return Result.success();
    }

    @GetMapping("/{documentId}")
    public Result getDocumentDetail(@PathVariable Long spaceId,
                                    @PathVariable Long documentId,
                                    @AuthenticationPrincipal LoginUser loginUser) {
        return Result.success(documentService.getDocumentDetail(spaceId, documentId, loginUser));
    }

    @GetMapping("/{documentId}/download")
    public Result downloadDocument(@PathVariable Long spaceId,
                                   @PathVariable Long documentId,
                                   @AuthenticationPrincipal LoginUser loginUser) {
        String url = documentService.downloadDocument(spaceId, documentId, loginUser);
        return Result.success(url);
    }

    @GetMapping("/{documentId}/preview")
    public Result previewDocument(@PathVariable Long spaceId,
                                  @PathVariable Long documentId,
                                  @AuthenticationPrincipal LoginUser loginUser) {
        return Result.success(documentService.previewDocument(spaceId, documentId, loginUser));
    }

    @GetMapping("trash")
    public Result listTrashedDocuments(@PathVariable Long spaceId,
                                       @Validated @ModelAttribute PageQuery pageQuery,
                                       @AuthenticationPrincipal LoginUser loginUser) {
        return Result.success(documentService.listTrashedDocuments(spaceId, pageQuery, loginUser));
    }

    @PutMapping("/{documentId}/restore")
    public Result restoreDocument(@PathVariable Long spaceId,
                                  @PathVariable Long documentId,
                                  @RequestBody(required = false) RestoreDocumentDTO dto,
                                  @AuthenticationPrincipal LoginUser loginUser) {
        // 未指定目标时优先恢复原目录，原目录不存在则由 Service 回退到根目录。
        Long targetFolderId = dto != null ? dto.getTargetFolderId() : null;
        return Result.success(documentService.restoreDocument(spaceId, documentId, targetFolderId, loginUser));
    }

    @DeleteMapping("/{documentId}/purge")
    public Result purgeDocument(@PathVariable Long spaceId,
                                @PathVariable Long documentId,
                                @AuthenticationPrincipal LoginUser loginUser) {
        documentService.purgeDocument(spaceId, documentId, loginUser);
        return Result.success();
    }

    @GetMapping("/search")
    public Result searchDocuments(@PathVariable Long spaceId,
                                  @RequestParam String keyword,
                                  @Validated @ModelAttribute PageQuery pageQuery,
                                  @AuthenticationPrincipal LoginUser loginUser) {
        return Result.success(documentService.searchDocuments(spaceId, keyword, pageQuery, loginUser));
    }
}
