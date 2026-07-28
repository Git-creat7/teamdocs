package asia.creat.teamdocsbackend.service.impl;

import asia.creat.common.exception.BusinessException;
import asia.creat.dto.AddCommentDTO;
import asia.creat.entity.Comment;
import asia.creat.entity.Document;
import asia.creat.entity.SpaceMember;
import asia.creat.entity.SpaceRole;
import asia.creat.helper.ResourcePermissionHelper;
import asia.creat.mapper.CommentMapper;
import asia.creat.mapper.DocumentMapper;
import asia.creat.security.LoginUser;
import asia.creat.security.SpaceContext;
import asia.creat.service.impl.CommentServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommentServiceImplTest {
    private static final Long SPACE_ID = 1L;
    private static final Long DOCUMENT_ID = 10L;
    private static final Long OTHER_DOCUMENT_ID = 11L;
    private static final Long USER_ID = 7L;
    private static final Long OTHER_USER_ID = 8L;
    private static final Long REPLY_ID = 20L;
    private static final Long COMMENT_ID = 30L;
    private static final LoginUser LOGIN_USER = new LoginUser(USER_ID, "alice");

    @Mock
    private CommentMapper commentMapper;

    @Mock
    private DocumentMapper documentMapper;

    private CommentServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CommentServiceImpl(commentMapper, documentMapper, new ResourcePermissionHelper());
        SpaceMember member = new SpaceMember();
        member.setRole(SpaceRole.MEMBER);
        SpaceContext.set(member);
    }

    @AfterEach
    void tearDown() {
        SpaceContext.clear();
    }

    @Test
    void addReplyShouldPersistTrimmedContent() {
        allowDocument();
        when(commentMapper.selectById(REPLY_ID)).thenReturn(comment(REPLY_ID, DOCUMENT_ID, OTHER_USER_ID, 0));

        service.addComment(
                SPACE_ID,
                DOCUMENT_ID,
                new AddCommentDTO("  回复内容  ", REPLY_ID),
                LOGIN_USER
        );

        ArgumentCaptor<Comment> captor = ArgumentCaptor.forClass(Comment.class);
        verify(commentMapper).insert(captor.capture());
        Comment inserted = captor.getValue();
        assertEquals(DOCUMENT_ID, inserted.getDocumentId());
        assertEquals(USER_ID, inserted.getUserId());
        assertEquals("回复内容", inserted.getContent());
        assertEquals(REPLY_ID, inserted.getReplyToId());
    }

    @Test
    void addReplyShouldRejectMissingTarget() {
        allowDocument();

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.addComment(
                        SPACE_ID,
                        DOCUMENT_ID,
                        new AddCommentDTO("回复", REPLY_ID),
                        LOGIN_USER
                )
        );

        assertEquals("该评论不存在或已被删除，无法回复", exception.getMessage());
        verify(commentMapper, never()).insert(any(Comment.class));
    }

    @Test
    void addReplyShouldRejectTargetFromAnotherDocument() {
        allowDocument();
        when(commentMapper.selectById(REPLY_ID))
                .thenReturn(comment(REPLY_ID, OTHER_DOCUMENT_ID, OTHER_USER_ID, 0));

        assertThrows(
                BusinessException.class,
                () -> service.addComment(
                        SPACE_ID,
                        DOCUMENT_ID,
                        new AddCommentDTO("回复", REPLY_ID),
                        LOGIN_USER
                )
        );

        verify(commentMapper, never()).insert(any(Comment.class));
    }

    @Test
    void addReplyShouldRejectDeletedTarget() {
        allowDocument();
        when(commentMapper.selectById(REPLY_ID)).thenReturn(comment(REPLY_ID, DOCUMENT_ID, OTHER_USER_ID, 1));

        assertThrows(
                BusinessException.class,
                () -> service.addComment(
                        SPACE_ID,
                        DOCUMENT_ID,
                        new AddCommentDTO("回复", REPLY_ID),
                        LOGIN_USER
                )
        );

        verify(commentMapper, never()).insert(any(Comment.class));
    }

    @Test
    void deleteShouldAllowAuthor() {
        allowDocument();
        when(commentMapper.selectById(COMMENT_ID)).thenReturn(comment(COMMENT_ID, DOCUMENT_ID, USER_ID, 0));

        service.deleteComment(SPACE_ID, DOCUMENT_ID, COMMENT_ID, LOGIN_USER);

        ArgumentCaptor<Comment> captor = ArgumentCaptor.forClass(Comment.class);
        verify(commentMapper).updateById(captor.capture());
        assertEquals(1, captor.getValue().getDeleted());
    }

    @Test
    void deleteShouldAllowAdmin() {
        allowDocument();
        SpaceContext.getSpaceMember().setRole(SpaceRole.ADMIN);
        when(commentMapper.selectById(COMMENT_ID))
                .thenReturn(comment(COMMENT_ID, DOCUMENT_ID, OTHER_USER_ID, 0));

        service.deleteComment(SPACE_ID, DOCUMENT_ID, COMMENT_ID, LOGIN_USER);

        verify(commentMapper).updateById(any(Comment.class));
    }

    @Test
    void deleteShouldRejectMemberDeletingAnotherUsersComment() {
        allowDocument();
        when(commentMapper.selectById(COMMENT_ID))
                .thenReturn(comment(COMMENT_ID, DOCUMENT_ID, OTHER_USER_ID, 0));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.deleteComment(SPACE_ID, DOCUMENT_ID, COMMENT_ID, LOGIN_USER)
        );

        assertEquals("没有权限操作该资源", exception.getMessage());
        verify(commentMapper, never()).updateById(any(Comment.class));
    }

    @Test
    void deleteShouldRejectCommentFromAnotherDocument() {
        allowDocument();
        when(commentMapper.selectById(COMMENT_ID))
                .thenReturn(comment(COMMENT_ID, OTHER_DOCUMENT_ID, USER_ID, 0));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.deleteComment(SPACE_ID, DOCUMENT_ID, COMMENT_ID, LOGIN_USER)
        );

        assertEquals("评论不存在", exception.getMessage());
        verify(commentMapper, never()).updateById(any(Comment.class));
    }

    private void allowDocument() {
        Document document = new Document();
        document.setId(DOCUMENT_ID);
        document.setSpaceId(SPACE_ID);
        when(documentMapper.selectById(DOCUMENT_ID)).thenReturn(document);
    }

    private Comment comment(Long id, Long documentId, Long userId, Integer deleted) {
        Comment comment = new Comment();
        comment.setId(id);
        comment.setDocumentId(documentId);
        comment.setUserId(userId);
        comment.setDeleted(deleted);
        return comment;
    }
}
