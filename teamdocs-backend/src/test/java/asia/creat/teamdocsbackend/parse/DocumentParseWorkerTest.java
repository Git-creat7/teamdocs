package asia.creat.teamdocsbackend.parse;

import asia.creat.config.ParseProperties;
import asia.creat.entity.Document;
import asia.creat.mapper.DocumentMapper;
import asia.creat.parse.DocumentParseWorker;
import asia.creat.service.DocumentParseService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentParseWorkerTest {

    @Mock
    private DocumentMapper documentMapper;
    @Mock
    private DocumentParseService documentParseService;

    @BeforeAll
    static void initializeTableMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Document.class);
    }

    @Test
    void scanClaimsInsideExecutor() {
        ParseProperties properties = new ParseProperties();
        properties.setBatchSize(5);
        DocumentParseWorker worker = new DocumentParseWorker(
                documentMapper,
                documentParseService,
                properties,
                Runnable::run
        );
        Page<Document> page = new Page<>();
        Document first = new Document();
        first.setId(9L);
        Document second = new Document();
        second.setId(10L);
        page.setRecords(List.of(first, second));
        when(documentMapper.selectPage(any(), any())).thenReturn(page);

        worker.scanPending();

        verify(documentParseService).parseDocument(9L);
        verify(documentParseService).parseDocument(10L);
    }

    @Test
    void timeoutDoesNotBumpParseVersion() {
        ParseProperties properties = new ParseProperties();
        properties.setTimeoutSeconds(120);
        DocumentParseWorker worker = new DocumentParseWorker(
                documentMapper,
                documentParseService,
                properties,
                Runnable::run
        );
        when(documentMapper.update(isNull(), any())).thenReturn(1);
        Document timedOut = new Document();
        timedOut.setId(9L);
        timedOut.setParseVersion(3);
        Page<Document> page = new Page<>();
        page.setRecords(List.of(timedOut));
        when(documentMapper.selectPage(any(), any())).thenReturn(page);

        worker.failTimedOut();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<Document>> wrapper = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(documentMapper).update(isNull(), wrapper.capture());
        assertFalse(wrapper.getValue().getSqlSet().contains("parse_version"));
    }
}
