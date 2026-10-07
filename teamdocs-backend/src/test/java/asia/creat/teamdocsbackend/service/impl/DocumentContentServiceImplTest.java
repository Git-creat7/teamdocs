package asia.creat.teamdocsbackend.service.impl;

import asia.creat.entity.Document;
import asia.creat.entity.DocumentContent;
import asia.creat.entity.ParseStatus;
import asia.creat.entity.Space;
import asia.creat.mapper.DocumentContentMapper;
import asia.creat.mapper.DocumentMapper;
import asia.creat.mapper.SpaceMapper;
import asia.creat.service.impl.DocumentContentServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import org.mockito.ArgumentCaptor;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentContentServiceImplTest {

    @Mock
    private DocumentContentMapper documentContentMapper;

    @Mock
    private DocumentMapper documentMapper;

    @Mock
    private SpaceMapper spaceMapper;

    private DocumentContentServiceImpl service;

    @BeforeAll
    static void initializeTableMetadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();

        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), DocumentContent.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), Document.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), Space.class);
    }

    @BeforeEach
    void setUp() {
        service = new DocumentContentServiceImpl(documentContentMapper, documentMapper, spaceMapper);
    }

    @Test
    void recordParsingShouldUpdateDocumentStatus() {
        service.recordParsing(100L);
        verify(documentMapper).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void saveChunksShouldDeleteOldChunksAndSaveNewChunks() {
        DocumentContent chunk1 = new DocumentContent();
        chunk1.setChunkIndex(0);
        chunk1.setContent("Chunk 1 content");

        DocumentContent chunk2 = new DocumentContent();
        chunk2.setChunkIndex(1);
        chunk2.setContent("Chunk 2 content");

        service.saveChunks(100L, 1L, List.of(chunk1, chunk2));

        verifyDeletesOnlyDocument(100L);
        verify(documentContentMapper).insert(chunk1);
        verify(documentContentMapper).insert(chunk2);

        assertEquals(100L, chunk1.getDocumentId());
        assertEquals(1L, chunk1.getSpaceId());

        verify(documentMapper).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void recordParseFailureShouldUpdateDocumentStatus() {
        service.recordParseFailure(100L, "Failed to parse PDF file");
        verify(documentMapper).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void resetParseStatusShouldResetDocumentStatus() {
        service.resetParseStatus(100L);
        verify(documentMapper).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void publishShouldRejectStaleVersionWithoutReplacingChunks() {
        Document locked = parsingDocument(2);

        when(documentMapper.lockById(100L)).thenReturn(locked);

        boolean published = service.publishIfParsing(100L, 1L, 1, List.of(chunk("正文")));

        assertFalse(published);
        verify(documentContentMapper, never()).delete(any());
        verify(documentContentMapper, never()).insert(any(DocumentContent.class));
    }

    @Test
    void publishShouldReplaceChunksWhenVersionMatches() {
        when(documentMapper.lockById(100L)).thenReturn(parsingDocument(3));
        when(spaceMapper.selectById(1L)).thenReturn(new Space());
        when(documentMapper.update(isNull(), any())).thenReturn(1);

        DocumentContent chunk = chunk("正文");

        boolean published = service.publishIfParsing(100L, 1L, 3, List.of(chunk));

        assertTrue(published);
        verifyDeletesOnlyDocument(100L);
        verify(documentContentMapper).insert(chunk);

        assertEquals(100L, chunk.getDocumentId());
        assertEquals(1L, chunk.getSpaceId());
    }

    @Test
    void publishShouldIgnoreDeletedDocument() {
        Document locked = parsingDocument(3);
        locked.setDeleted(1);
        when(documentMapper.lockById(100L)).thenReturn(locked);

        assertFalse(service.publishIfParsing(100L, 1L, 3, List.of(chunk("正文"))));
        verify(documentContentMapper, never()).insert(any(DocumentContent.class));
    }

    @Test
    void publishShouldRollBackWhenStatusUpdateMisses() {
        when(documentMapper.lockById(100L)).thenReturn(parsingDocument(3));
        when(spaceMapper.selectById(1L)).thenReturn(new Space());
        when(documentMapper.update(isNull(), any())).thenReturn(0);

        assertThrows(IllegalStateException.class,
                () -> service.publishIfParsing(100L, 1L, 3, List.of(chunk("正文"))));
    }

    @Test
    void getChunksShouldIgnoreDocumentThatIsNotReady() {
        Document document = new Document();
        document.setParseStatus(ParseStatus.PARSING);
        document.setSpaceId(1L);
        when(documentMapper.selectById(100L)).thenReturn(document);

        assertTrue(service.getChunksByDocumentId(100L).isEmpty());
        verify(documentContentMapper, never()).selectList(any());
    }

    @Test
    void purgeByDocumentIdShouldDeleteChunks() {
        service.purgeByDocumentId(100L);
        verifyDeletesOnlyDocument(100L);
    }

    /** 删除方式可调整，但条件必须始终限定当前文档。 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void verifyDeletesOnlyDocument(Long documentId) {
        ArgumentCaptor<Wrapper<DocumentContent>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(documentContentMapper).delete(captor.capture());
        Wrapper<DocumentContent> wrapper = captor.getValue();

        assertTrue(wrapper.getSqlSegment().contains("document_id ="));
        var parameters = ((AbstractWrapper<?, ?, ?>) wrapper).getParamNameValuePairs();
        assertEquals(1, parameters.size());
        assertTrue(parameters.containsValue(documentId));
    }

    private Document parsingDocument(int version) {
        Document document = new Document();
        document.setId(100L);
        document.setSpaceId(1L);
        document.setDeleted(0);
        document.setParseStatus(ParseStatus.PARSING);
        document.setParseVersion(version);

        return document;
    }

    private DocumentContent chunk(String content) {
        DocumentContent chunk = new DocumentContent();
        chunk.setChunkIndex(0);
        chunk.setContent(content);

        return chunk;
    }
}
