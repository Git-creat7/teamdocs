package asia.creat.service;

import asia.creat.dto.AddCommentDTO;
import asia.creat.security.LoginUser;
import asia.creat.vo.CommentVO;

import java.util.List;

public interface CommentService {
    void addComment(Long spaceId, Long documentId, AddCommentDTO dto, LoginUser loginUser);

    List<CommentVO> listComments(Long spaceId, Long documentId, LoginUser loginUser);

    void deleteComment(Long spaceId, Long documentId, Long commentId, LoginUser loginUser);
}
