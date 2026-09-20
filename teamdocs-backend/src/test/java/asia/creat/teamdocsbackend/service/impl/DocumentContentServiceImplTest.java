package asia.creat.teamdocsbackend.service.impl;

import asia.creat.entity.Document;
import asia.creat.entity.DocumentContent;
import asia.creat.entity.ParseStatus;
import asia.creat.mapper.DocumentContentMapper;
import asia.creat.mapper.DocumentMapper;
import asia.creat.service.impl.DocumentContentServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DocumentContentServiceImplTest {

    @Mock
    private DocumentContentMapper documentContentMapper;

    @Mock
    private DocumentMapper documentMapper;

    private DocumentContentServiceImpl service;

    @BeforeAll
    static void initializeTableMetadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), DocumentContent.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), Document.class);
    }

    @BeforeEach
    void setUp() {
        service = new DocumentContentServiceImpl(documentContentMapper, documentMapper);
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

        verify(documentContentMapper).delete(any(LambdaQueryWrapper.class));
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
    void purgeByDocumentIdShouldDeleteChunks() {
        service.purgeByDocumentId(100L);
        verify(documentContentMapper).delete(any(LambdaQueryWrapper.class));
    }
}
