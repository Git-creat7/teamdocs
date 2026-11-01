package asia.creat.teamdocsbackend.parse;

import asia.creat.agent.AgentWorker;
import asia.creat.config.ParseProperties;
import asia.creat.entity.Document;
import asia.creat.entity.DocumentContent;
import asia.creat.entity.ParseStatus;
import asia.creat.mapper.DocumentContentMapper;
import asia.creat.mapper.DocumentMapper;
import asia.creat.parse.DocumentSummaryWorker;
import asia.creat.service.DocumentSummaryService;
import asia.creat.service.impl.DocumentSummaryServiceImpl;
import asia.creat.utils.CacheClient;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentSummaryWorkerTest {

    @Mock
    private DocumentMapper documentMapper;
    @Mock
    private DocumentSummaryService summaryService;
    @Mock
    private DocumentContentMapper contentMapper;
    @Mock
    private ChatLanguageModel model;
    @Mock
    private CacheClient cacheClient;
    @Mock
    private AgentWorker agentWorker;

    private ParseProperties properties;
    private DocumentSummaryWorker worker;

    @BeforeEach
    void setUp() {
        properties = new ParseProperties();
        worker = new DocumentSummaryWorker(documentMapper, summaryService, properties);
    }

    @Test
    void disabledSummaryDoesNotScanOrReset() {
        properties.setSummaryEnabled(false);

        worker.scanPending();
        worker.resetTimeoutTasks();

        verify(documentMapper, never()).findPendingSummaryDocuments(anyInt(), anyLong());
        verify(summaryService, never()).resetTimeoutTasks();
    }

    @Test
    void disabledParseDoesNotScanOrReset() {
        properties.setEnabled(false);

        worker.scanPending();
        worker.resetTimeoutTasks();

        verify(documentMapper, never()).findPendingSummaryDocuments(anyInt(), anyLong());
        verify(summaryService, never()).resetTimeoutTasks();
    }

    @Test
    void scanClaimsAndDelegatesToService() {
        Document d1 = new Document();
        d1.setId(101L);
        Document d2 = new Document();
        d2.setId(102L);

        when(documentMapper.findPendingSummaryDocuments(anyInt(), anyLong()))
                .thenReturn(List.of(d1, d2));

        worker.scanPending();

        verify(summaryService).processDocumentSummary(101L);
        verify(summaryService).processDocumentSummary(102L);
    }

    @Test
    void serviceProcessesSummarySuccessfullyWithModel() {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("chatLanguageModel", model);
        beanFactory.addBean("agentWorker", agentWorker);

        DocumentSummaryServiceImpl impl = new DocumentSummaryServiceImpl(
                documentMapper,
                contentMapper,
                properties,
                beanFactory.getBeanProvider(ChatLanguageModel.class),
                cacheClient,
                beanFactory.getBeanProvider(AgentWorker.class)
        );

        Document doc = Document.builder()
                .id(201L)
                .spaceId(1L)
                .name("开发规范.md")
                .parseStatus(ParseStatus.READY)
                .deleted(0)
                .parseVersion(3)
                .summary("规则摘要备份")
                .build();

        when(documentMapper.selectById(201L)).thenReturn(doc);
        when(documentMapper.claimSummaryTask(eq(201L), eq(3), anyString(), anyLong(), anyLong())).thenReturn(1);

        DocumentContent chunk = DocumentContent.builder()
                .documentId(201L)
                .content("本文档规定了 Java 核心开发规范，统一使用 Spring Boot。")
                .build();
        when(contentMapper.selectList(any())).thenReturn(List.of(chunk));

        when(model.generate(anyList())).thenReturn(
                Response.from(AiMessage.from("{\"summary\":\"Java核心规范\",\"keywords\":[\"Java\",\"规范\"]}"))
        );

        when(documentMapper.completeSummary(eq(201L), eq(3), eq("Java核心规范"), eq("Java,规范"), eq("MODEL"), eq("SUCCEEDED"), any(), anyString()))
                .thenReturn(1);

        boolean ok = impl.processDocumentSummary(201L);
        assertTrue(ok);

        verify(agentWorker).invalidateSpaceCache(1L);
    }

    @Test
    void serviceRetainsRuleSummaryOnModelFailure() {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("chatLanguageModel", model);

        DocumentSummaryServiceImpl impl = new DocumentSummaryServiceImpl(
                documentMapper,
                contentMapper,
                properties,
                beanFactory.getBeanProvider(ChatLanguageModel.class),
                cacheClient,
                beanFactory.getBeanProvider(AgentWorker.class)
        );

        Document doc = Document.builder()
                .id(202L)
                .spaceId(1L)
                .name("故障手册.md")
                .parseStatus(ParseStatus.READY)
                .deleted(0)
                .parseVersion(2)
                .summary("原始规则摘要内容")
                .build();

        when(documentMapper.selectById(202L)).thenReturn(doc);
        when(documentMapper.claimSummaryTask(eq(202L), eq(2), anyString(), anyLong(), anyLong())).thenReturn(1);
        when(contentMapper.selectList(any())).thenReturn(List.of(DocumentContent.builder().content("故障恢复").build()));
        when(model.generate(anyList())).thenThrow(new RuntimeException("模型连接超时"));

        boolean ok = impl.processDocumentSummary(202L);
        assertFalse(ok);

        // 验证失败时保留了原始规则摘要，标记为 FAILED
        ArgumentCaptor<String> summaryCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> sourceCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> stateCaptor = ArgumentCaptor.forClass(String.class);

        verify(documentMapper).completeSummary(eq(202L), eq(2), summaryCaptor.capture(), any(), sourceCaptor.capture(), stateCaptor.capture(), anyString(), anyString());
        assertEquals("原始规则摘要内容", summaryCaptor.getValue());
        assertEquals("RULE", sourceCaptor.getValue());
        assertEquals("FAILED", stateCaptor.getValue());
    }

    @Test
    void serviceSkipsWhenClaimFailsDueToConcurrentWorkerOrVersionMismatch() {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("chatLanguageModel", model);

        DocumentSummaryServiceImpl impl = new DocumentSummaryServiceImpl(
                documentMapper,
                contentMapper,
                properties,
                beanFactory.getBeanProvider(ChatLanguageModel.class),
                cacheClient,
                beanFactory.getBeanProvider(AgentWorker.class)
        );

        Document doc = Document.builder()
                .id(203L)
                .parseStatus(ParseStatus.READY)
                .deleted(0)
                .parseVersion(1)
                .build();

        when(documentMapper.selectById(203L)).thenReturn(doc);
        // 竞争失败，claim 返回 0
        when(documentMapper.claimSummaryTask(eq(203L), eq(1), anyString(), anyLong(), anyLong())).thenReturn(0);

        boolean ok = impl.processDocumentSummary(203L);
        assertFalse(ok);
        verify(model, never()).generate(anyList());
    }
}
