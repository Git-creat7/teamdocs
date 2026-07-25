package asia.creat.teamdocsbackend.service.impl;

import asia.creat.common.PageResult;
import asia.creat.dto.PageQuery;
import asia.creat.entity.Document;
import asia.creat.helper.ResourcePermissionHelper;
import asia.creat.mapper.CommentMapper;
import asia.creat.mapper.DocumentMapper;
import asia.creat.service.impl.CommentServiceImpl;
import asia.creat.vo.CommentVO;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommentServiceImplPaginationTest {

    @Mock
    private CommentMapper commentMapper;

    @Mock
    private DocumentMapper documentMapper;

    @Mock
    private ResourcePermissionHelper permissionHelper;

    private CommentServiceImpl commentService;

    @BeforeEach
    void setUp() {
        commentService = new CommentServiceImpl(commentMapper, documentMapper, permissionHelper);
    }

    @Test
    void listCommentsShouldPassRequestedPageToMapper() {
        long spaceId = 2L;
        long documentId = 3L;
        Document document = new Document();
        document.setSpaceId(spaceId);
        when(documentMapper.selectById(documentId)).thenReturn(document);

        CommentVO comment = new CommentVO();
        Page<CommentVO> mapperResult = new Page<>(4, 6);
        mapperResult.setRecords(List.of(comment));
        mapperResult.setTotal(13);
        when(commentMapper.listByDocumentId(any(IPage.class), eq(documentId))).thenReturn(mapperResult);

        PageQuery pageQuery = new PageQuery();
        pageQuery.setCurrent(4);
        pageQuery.setSize(6);

        PageResult<CommentVO> result = commentService.listComments(
                spaceId, documentId, pageQuery, null
        );

        @SuppressWarnings("unchecked")
        ArgumentCaptor<IPage<CommentVO>> pageCaptor = ArgumentCaptor.forClass(IPage.class);
        verify(commentMapper).listByDocumentId(pageCaptor.capture(), eq(documentId));
        assertEquals(4, pageCaptor.getValue().getCurrent());
        assertEquals(6, pageCaptor.getValue().getSize());
        assertEquals(13, result.getTotal());
        assertEquals(3, result.getPages());
        assertEquals(List.of(comment), result.getRecords());
    }
}
