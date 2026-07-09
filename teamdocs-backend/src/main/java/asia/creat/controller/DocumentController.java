package asia.creat.controller;

import asia.creat.common.Result;
import asia.creat.dto.MoveDocumentDTO;
import asia.creat.dto.RenameDocumentDTO;
import asia.creat.dto.RestoreDocumentDTO;
import asia.creat.entity.Document;
import asia.creat.security.LoginUser;
import asia.creat.service.DocumentService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/spaces/{spaceId}/documents")
public class DocumentController {
    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    /*
     * 上传文档
     * */
    @PostMapping("/upload")
    public Result uploadDocument(@PathVariable Long spaceId,
                                 @RequestParam(defaultValue = "0") Long folderId,
                                 @RequestParam MultipartFile file,
                                 @AuthenticationPrincipal LoginUser loginUser) {
        documentService.upload(spaceId, folderId, file, loginUser);
        return Result.success();
    }

    /*
     * 获取文件夹下的文档列表
     * */
    @GetMapping("")
    public Result listByFolder(@PathVariable Long spaceId,
                               @RequestParam(defaultValue = "0") Long folderId,
                               @AuthenticationPrincipal LoginUser loginUser) {
        return Result.success(documentService.listByFolder(spaceId, folderId, loginUser));
    }

    /*
     * 删除文档
     * */
    @DeleteMapping("/{documentId}")
    public Result deleteDocument(@PathVariable Long spaceId,
                                 @PathVariable Long documentId,
                                 @AuthenticationPrincipal LoginUser loginUser) {
        documentService.deleteDocument(spaceId, documentId, loginUser);
        return Result.success();
    }

    /*
     * 重命名文档
     * */
    @PutMapping("/{documentId}/rename")
    public Result renameDocument(@PathVariable Long spaceId,
                                 @PathVariable Long documentId,
                                 @RequestBody @Validated RenameDocumentDTO dto,
                                 @AuthenticationPrincipal LoginUser loginUser) {
        documentService.renameDocument(spaceId, documentId, dto, loginUser);
        return Result.success();
    }

    /*
     * 移动文档
     * */
    @PutMapping("/{documentId}/move")
    public Result moveDocument(@PathVariable Long spaceId,
                               @PathVariable Long documentId,
                               @RequestBody @Validated MoveDocumentDTO dto,
                               @AuthenticationPrincipal LoginUser loginUser) {
        documentService.moveDocument(spaceId, documentId, dto, loginUser);
        return Result.success();
    }

    /*
     * 下载文档
     * */
    @GetMapping("/{documentId}/download")
    public Result downloadDocument(@PathVariable Long spaceId,
                                   @PathVariable Long documentId,
                                   @AuthenticationPrincipal LoginUser loginUser) {
        String url = documentService.downloadDocument(spaceId, documentId, loginUser);
        return Result.success(url);
    }

    /*
     * 回收站：查表
     * */
    @GetMapping("trash")
    public Result listTrashedDocuments(@PathVariable Long spaceId,
                                       @AuthenticationPrincipal LoginUser loginUser) {
        List<Document> list = documentService.listTrashedDocuments(spaceId, loginUser);
        return Result.success(list);
    }

    /*
     * 回收站:恢复文件
     * */
    @PutMapping("/{documentId}/restore")
    public Result restoreDocument(@PathVariable Long spaceId,
                                  @PathVariable Long documentId,
                                  @RequestBody(required = false) RestoreDocumentDTO dto,
                                  @AuthenticationPrincipal LoginUser loginUser) {
        //如果dto为null，说明用户没有提供目标文件夹ID，默认恢复到根目录（targetFolderId为null）
        Long targetFolderId = dto != null ? dto.getTargetFolderId() : null;
        documentService.restoreDocument(spaceId, documentId, targetFolderId, loginUser);
        return Result.success();
    }

    /*
     * 回收站:彻底删除
     * */
    @DeleteMapping("/{documentId}/purge")
    public Result purgeDocument(@PathVariable Long spaceId,
                                @PathVariable Long documentId,
                                @AuthenticationPrincipal LoginUser loginUser) {
        documentService.purgeDocument(spaceId, documentId, loginUser);
        return Result.success();
    }

    /*
     * 通过name + description + tag模糊搜索到文档
     * */
    @GetMapping("/search")
    public Result searchDocuments(@PathVariable Long spaceId,
                                  @RequestParam String keyword,
                                  @AuthenticationPrincipal LoginUser loginUser) {
        List<Document> list = documentService.searchDocuments(spaceId, keyword, loginUser);
        return Result.success(list);
    }
}
/*
 * ps:有些地方该用dto,后期优化一下
 * */