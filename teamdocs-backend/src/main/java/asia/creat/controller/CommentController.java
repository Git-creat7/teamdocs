package asia.creat.controller;

import asia.creat.common.Result;
import asia.creat.dto.AddCommentDTO;
import asia.creat.dto.PageQuery;
import asia.creat.security.LoginUser;
import asia.creat.service.CommentService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/spaces/{spaceId}/documents/{documentId}/comments")
public class CommentController {
    private final CommentService commentService;

    public CommentController(CommentService commentService) {
        this.commentService = commentService;
    }

    /*
     * 新评论
     * */
    @PostMapping("")
    public Result addComment(@PathVariable Long spaceId,
                             @PathVariable Long documentId,
                             @RequestBody @Validated AddCommentDTO dto,
                             @AuthenticationPrincipal LoginUser loginUser) {
        commentService.addComment(spaceId, documentId, dto, loginUser);
        return Result.success();
    }

    /*
     * 获取评论
     * */
    @GetMapping("")
    public Result listComments(@PathVariable Long spaceId,
                               @PathVariable Long documentId,
                               @Validated @ModelAttribute PageQuery pageQuery,
                               @AuthenticationPrincipal LoginUser loginUser) {
        return Result.success(commentService.listComments(spaceId, documentId, pageQuery, loginUser));
    }

    /*
     * 删除评论
     * */
    @DeleteMapping("/{commentId}")
    public Result deleteComment(@PathVariable Long spaceId,
                                @PathVariable Long documentId,
                                @PathVariable Long commentId,
                                @AuthenticationPrincipal LoginUser loginUser) {
        commentService.deleteComment(spaceId, documentId, commentId, loginUser);
        return Result.success();
    }
}
