package asia.creat.controller;

import asia.creat.common.Result;
import asia.creat.dto.CreateTagDTO;
import asia.creat.dto.PageQuery;
import asia.creat.entity.Tag;
import asia.creat.security.LoginUser;
import asia.creat.service.TagService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/spaces/{spaceId}")
public class TagController {

    private final TagService tagService;

    public TagController(TagService tagService) {
        this.tagService = tagService;
    }

    /*
    * 创建标签
    * */
    @PostMapping("/tags")
    public Result createTag(@PathVariable Long spaceId,
                            @RequestBody @Validated CreateTagDTO dto,
                            @AuthenticationPrincipal LoginUser loginUser) {
        tagService.createTag(spaceId, dto,loginUser);
        return Result.success();
    }

    /*
    * 获取标签列表
    * */
    @GetMapping("/tags")
    public Result getTags(@PathVariable Long spaceId,
                          @AuthenticationPrincipal LoginUser loginUser){
        List<Tag> list = tagService.getTags(spaceId,loginUser);
        return Result.success(list);
    }

    /*
    * 删除标签
    * */
    @DeleteMapping("/tags/{tagId}")
    public Result deleteTag(@PathVariable Long spaceId,
                            @PathVariable Long tagId,
                            @AuthenticationPrincipal LoginUser loginUser){
        tagService.deleteTag(spaceId, tagId, loginUser);
        return Result.success();
    }
    /*
    * 重命名标签
    * */
    @PutMapping("/tags/{tagId}")
    public Result renameTag(@PathVariable Long spaceId,
                            @PathVariable Long tagId,
                            @RequestParam String newName,
                            @AuthenticationPrincipal LoginUser loginUser){
        tagService.renameTag(spaceId, tagId, newName, loginUser);
        return Result.success();
    }

    /*
    * 打标签
    * */
    @PostMapping("/documents/{documentId}/tags/{tagId}")
    public Result addTagToDocument(@PathVariable Long spaceId,
                                   @PathVariable Long documentId,
                                   @PathVariable Long tagId,
                                   @AuthenticationPrincipal LoginUser loginUser){
        tagService.addTagToDocument(spaceId, documentId, tagId, loginUser);
        return Result.success();
    }

    /*
    * 摘标签
    * */
    @DeleteMapping("/documents/{documentId}/tags/{tagId}")
    public Result removeTagFromDocument(@PathVariable Long spaceId,
                                        @PathVariable Long documentId,
                                        @PathVariable Long tagId,
                                        @AuthenticationPrincipal LoginUser loginUser){
        tagService.removeTagFromDocument(spaceId, documentId, tagId, loginUser);
        return Result.success();
    }



    /*
    * 查单文档已打的标签
    * */
    @GetMapping("/documents/{documentId}/tags")
    public Result listTagsByDocument(@PathVariable Long spaceId,
                                     @PathVariable Long documentId,
                                     @AuthenticationPrincipal LoginUser loginUser) {
        return Result.success(tagService.listTagsByDocument(spaceId, documentId, loginUser));
    }

    /*
    * 批量查多文档标签：documentIds 逗号分隔，返回 { docId: Tag[] }
    * */
    @GetMapping("/documents/tags")
    public Result listTagsByDocuments(@PathVariable Long spaceId,
                                      @RequestParam List<Long> documentIds,
                                      @AuthenticationPrincipal LoginUser loginUser) {
        return Result.success(tagService.listTagsByDocuments(spaceId, documentIds, loginUser));
    }

    @GetMapping("/tags/{tagId}/documents")
    public Result listDocumentsByTag(@PathVariable Long spaceId,
                                     @PathVariable Long tagId,
                                     @Validated @ModelAttribute PageQuery pageQuery,
                                     @AuthenticationPrincipal LoginUser loginUser){
        return Result.success(tagService.listDocumentsByTag(spaceId, tagId, pageQuery, loginUser));
    }

}
