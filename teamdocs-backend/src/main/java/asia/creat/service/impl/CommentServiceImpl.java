package asia.creat.service.impl;

import asia.creat.anno.OperationLog;
import asia.creat.anno.OperationTarget;
import asia.creat.anno.RequireSpaceRole;
import asia.creat.anno.SpaceId;
import asia.creat.common.PageResult;
import asia.creat.common.exception.BusinessException;
import asia.creat.dto.AddCommentDTO;
import asia.creat.dto.PageQuery;
import asia.creat.entity.Comment;
import asia.creat.entity.Document;
import asia.creat.entity.SpaceMember;
import asia.creat.helper.ResourcePermissionHelper;
import asia.creat.mapper.CommentMapper;
import asia.creat.mapper.DocumentMapper;
import asia.creat.security.LoginUser;
import asia.creat.security.SpaceContext;
import asia.creat.vo.CommentVO;
import org.springframework.stereotype.Service;

@Service
public class CommentServiceImpl implements asia.creat.service.CommentService {
    private final CommentMapper commentMapper;
    private final DocumentMapper documentMapper;
    private final ResourcePermissionHelper permissionHelper;

    public CommentServiceImpl(CommentMapper commentMapper,DocumentMapper documentMapper, ResourcePermissionHelper permissionHelper) {
        this.commentMapper = commentMapper;
        this.documentMapper = documentMapper;
        this.permissionHelper = permissionHelper;
    }


    @Override
    @OperationLog(value = "添加评论", resourceType = "COMMENT", resourceName = "#dto.content")
    @RequireSpaceRole
    public void addComment(@SpaceId Long spaceId, Long documentId, AddCommentDTO dto, LoginUser loginUser) {
        checkDocument(spaceId, documentId);
        if (dto.getReplyToId() != null) {
            /*
            * 检查回复的评论是否存在
            * 检查该评论是否属于同一文档
            * 检查该评论是否已被删除
            */
            Comment replyComment = commentMapper.selectById(dto.getReplyToId());
            if (replyComment == null
                    || !replyComment.getDocumentId().equals(documentId)
                    || Integer.valueOf(1).equals(replyComment.getDeleted())) {
                /*
                * Integer.valueOf(1).equals(replyComment.getDeleted())
                * 审查：使用空安全的比较方式，避免未来脏数据导致自动拆箱空指针
                * */
                throw new BusinessException("该评论不存在或已被删除，无法回复");
            }
        }
        Comment comment = new Comment();
        comment.setDocumentId(documentId);
        comment.setUserId(loginUser.getUserId());
        comment.setContent(dto.getContent().strip());
        comment.setReplyToId(dto.getReplyToId());
        commentMapper.insert(comment);
    }

    @Override
    @RequireSpaceRole
    public PageResult<CommentVO> listComments(@SpaceId Long spaceId, Long documentId, PageQuery pageQuery, LoginUser loginUser) {
        checkDocument(spaceId, documentId);
        return PageResult.from(commentMapper.listByDocumentId(pageQuery.toPage(), documentId));
    }

    @Override
    @OperationLog(value = "删除评论", resourceType = "COMMENT")
    @RequireSpaceRole
    public void deleteComment(@SpaceId Long spaceId, Long documentId,@OperationTarget Long commentId, LoginUser loginUser) {
        checkDocument(spaceId, documentId);
        Comment comment = commentMapper.selectById(commentId);
        if (comment == null || !comment.getDocumentId().equals(documentId)) {
            throw new BusinessException("评论不存在");
        }

        SpaceMember member = SpaceContext.getSpaceMember();
        permissionHelper.checkOwnerOrCreator(member, comment.getUserId(), loginUser.getUserId());
        comment.setDeleted(1);
        commentMapper.updateById(comment);
    }


    private void checkDocument(Long spaceId, Long documentId){
        Document doc = documentMapper.selectById(documentId);
        if(doc == null || !doc.getSpaceId().equals(spaceId)){
            throw new BusinessException("文档不存在或不属于当前空间");
        }
    }
}
