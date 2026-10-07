package asia.creat.teamdocsbackend.model;

import asia.creat.agent.model.OpenAiReasoningChatModel;
import asia.creat.common.exception.BusinessException;
import asia.creat.config.*;
import asia.creat.model.*;
import asia.creat.retrieval.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.UnknownHostException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiDependencyHealthTest {
    @Test
    void diagnosticsDistinguishHttpNetworkConfigurationAndProtocolWithoutRawMessages() {
        for (int status : new int[]{400, 401, 403, 404, 429, 503}) {
            var model = mock(OpenAiReasoningChatModel.CallFailure.class);
            when(model.httpStatus()).thenReturn(status);
            String detail = ReflectionTestUtils.invokeMethod(
                    AiDependencyHealth.class, "describeFailure", model);
            assertTrue(detail.contains("HTTP " + status));
            assertTrue(detail.length() > 15);
        }
        String dns = ReflectionTestUtils.invokeMethod(
                AiDependencyHealth.class, "describeFailure", new UnknownHostException("private-url-token"));
        assertTrue(dns.contains("DNS"));
        assertFalse(dns.contains("private-url-token"));
        String config = ReflectionTestUtils.invokeMethod(AiDependencyHealth.class,
                "describeFailure", new BusinessException("请填写 API Key"));
        assertEquals("请填写 API Key", config);
        var failure = mock(OpenAiReasoningChatModel.CallFailure.class);
        when(failure.httpStatus()).thenReturn(null);
        when(failure.category()).thenReturn(OpenAiReasoningChatModel.FailureCategory.PROTOCOL);
        String protocol = ReflectionTestUtils.invokeMethod(AiDependencyHealth.class, "describeFailure", failure);
        assertTrue(protocol.contains("Chat Completions"));
    }

    @Test
    void viewingStatusDoesNotProbeAndRuntimeIsSeparateFromProbe() {
        var models = mock(UserModelService.class);
        when(models.view(1)).thenReturn(new UserModelData.View(false, 0, "", "", false, true));
        var embed = mock(SiliconFlowEmbeddingClient.class);
        var rerank = mock(SiliconFlowRerankClient.class);
        var vectors = mock(MilvusVectorClient.class);
        var service = new AiDependencyHealth(models, new AgentProperties(), new EmbeddingProperties(),
                new RerankProperties(), new MilvusProperties(), new ElasticsearchProperties(), new McpProperties(),
                embed, rerank, vectors, new ObjectMapper());
        var before = service.status(1);
        assertEquals("NOT_CONFIGURED", before.get(0).probe().status());
        assertEquals("UNTESTED", before.stream().filter(x -> x.id().equals("milvus")).findFirst().orElseThrow().probe().status());
        service.retrievalResult("milvus", false);
        var after = service.status(1).stream().filter(x -> x.id().equals("milvus")).findFirst().orElseThrow();
        assertEquals("DEGRADED", after.runtime().status());
        assertEquals("UNTESTED", after.probe().status());
        verifyNoInteractions(vectors);
    }

    @Test
    void completedProbesCanBeRepeatedImmediatelyWithoutLeakingRawErrors() {
        var models = mock(UserModelService.class);
        when(models.view(anyLong())).thenReturn(new UserModelData.View(false, 0, "", "", false, true));
        var vectors = mock(MilvusVectorClient.class);
        doThrow(new RuntimeException("private-key in exception")).when(vectors).checkHealth();
        var service = new AiDependencyHealth(models, new AgentProperties(), new EmbeddingProperties(),
                new RerankProperties(), new MilvusProperties(), new ElasticsearchProperties(), new McpProperties(),
                mock(SiliconFlowEmbeddingClient.class), mock(SiliconFlowRerankClient.class), vectors, new ObjectMapper());
        var failure = service.test(1, "milvus");
        assertEquals("ERROR", failure.status());
        assertFalse(failure.detail().contains("private-key"));
        assertEquals("ERROR", service.test(2, "milvus").status());
        assertEquals("ERROR", service.test(1, "milvus").status());
        verify(vectors, times(3)).checkHealth();
    }
}
