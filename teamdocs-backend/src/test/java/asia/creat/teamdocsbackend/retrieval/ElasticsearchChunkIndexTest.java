package asia.creat.teamdocsbackend.retrieval;

import asia.creat.config.ElasticsearchProperties;
import asia.creat.mapper.DocumentContentMapper;
import asia.creat.service.impl.ElasticsearchChunkIndex;
import asia.creat.vo.ChunkHitVO;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ElasticsearchChunkIndexTest {
    @Test
    void bulkItemErrorsAreNotReportedAsSuccessfulSyncOrRebuild() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("X-Elastic-Product", "Elasticsearch");
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            String body;
            if (exchange.getRequestURI().getPath().contains("_bulk")) {
                body = """
                        {"errors":true,"took":1,"items":[{"index":{"_index":"test-chunks","_id":"10_0","status":400,"error":{"type":"mapper_parsing_exception","reason":"test"}}}]}
                        """;
            } else if (exchange.getRequestURI().getPath().contains("_delete_by_query")) {
                body = """
                        {"took":0,"timed_out":false,"total":0,"deleted":0,"batches":0,"version_conflicts":0,"noops":0,"retries":{"bulk":0,"search":0},"throttled_millis":0,"requests_per_second":-1,"throttled_until_millis":0,"failures":[]}
                        """;
            } else {
                body = "{\"acknowledged\":true}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            boolean head = exchange.getRequestMethod().equals("HEAD");
            exchange.sendResponseHeaders(200, head ? -1 : bytes.length);
            if (!head) exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        DocumentContentMapper mapper = mock(DocumentContentMapper.class);
        ChunkHitVO row = new ChunkHitVO();
        row.setChunkId(99L); row.setDocumentId(10L); row.setSpaceId(1L);
        row.setChunkIndex(0); row.setParseVersion(1); row.setExcerpt("text");
        when(mapper.listIndexableDocumentChunks(10L)).thenReturn(List.of(row));
        when(mapper.listIndexableChunks(0L, 200)).thenReturn(List.of(row));
        ElasticsearchProperties properties = new ElasticsearchProperties();
        properties.setUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setIndex("test-chunks");
        ElasticsearchChunkIndex index = new ElasticsearchChunkIndex(properties, mapper);
        index.start();
        try {
            assertThrows(IllegalStateException.class, () -> index.syncDocument(10L));
            assertThrows(IllegalStateException.class, index::rebuild);
        } finally {
            index.close();
            server.stop(0);
        }
    }
}
