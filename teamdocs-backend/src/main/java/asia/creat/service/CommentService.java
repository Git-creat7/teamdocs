package asia.creat.service;

import asia.creat.common.PageResult;
import asia.creat.dto.AddCommentDTO;
import asia.creat.dto.PageQuery;
import asia.creat.security.LoginUser;
import asia.creat.vo.CommentVO;

public interface CommentService {
    void addComment(Long spaceId, Long documentId, AddCommentDTO dto, LoginUser loginUser);

    PageResult<CommentVO> listComments(Long spaceId, Long documentId, PageQuery pageQuery, LoginUser loginUser);

    void deleteComment(Long spaceId, Long documentId, Long commentId, LoginUser loginUser);
}
