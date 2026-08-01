package asia.creat.controller;

import asia.creat.common.Result;
import asia.creat.dto.CreateFolderDTO;
import asia.creat.dto.MoveFolderDTO;
import asia.creat.dto.RenameFolderDTO;
import asia.creat.security.LoginUser;
import asia.creat.service.FolderService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/spaces/{spaceId}")
@RequiredArgsConstructor
public class FolderController {
    private final FolderService folderService;

    @PostMapping("/folders")
    public Result createFolder(@PathVariable Long spaceId,
                               @RequestBody @Validated CreateFolderDTO dto,
                               @AuthenticationPrincipal LoginUser loginUser) {
        folderService.createFolder(spaceId, dto, loginUser);
        return Result.success();
    }

    @GetMapping("/folders")
    public Result getSubFolders(@PathVariable Long spaceId,
                                @RequestParam(required = false, defaultValue = "0") Long parentId,
                                @AuthenticationPrincipal LoginUser loginUser) {
        return Result.success(folderService.getSubFolder(spaceId, parentId, loginUser));
    }

    @PutMapping("/folders/{folderId}")
    public Result renameFolder(@PathVariable Long spaceId,
                               @PathVariable Long folderId,
                               @RequestBody @Validated RenameFolderDTO dto,
                               @AuthenticationPrincipal LoginUser loginUser) {
        folderService.renameFolder(spaceId, folderId, dto, loginUser);
        return Result.success();
    }

    @DeleteMapping("/folders/{folderId}")
    public Result deleteFolder(@PathVariable Long spaceId,
                               @PathVariable Long folderId,
                               @AuthenticationPrincipal LoginUser loginUser) {
        folderService.deleteFolder(spaceId, folderId, loginUser);
        return Result.success();
    }

    @PutMapping("/folders/{folderId}/move")
    public Result moveFolder(@PathVariable Long spaceId,
                             @PathVariable Long folderId,
                             @RequestBody @Validated MoveFolderDTO dto,
                             @AuthenticationPrincipal LoginUser loginUser) {
        folderService.moveFolder(spaceId, folderId, dto, loginUser);
        return Result.success();
    }

}
