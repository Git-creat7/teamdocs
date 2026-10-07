package asia.creat.teamdocsbackend.retrieval;

import asia.creat.config.EmbeddingProperties;
import asia.creat.config.MilvusProperties;
import asia.creat.retrieval.MilvusVectorClient;
import asia.creat.retrieval.RetrievalHttp;
import asia.creat.vo.ChunkHitVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 显式开启后启动三个独立容器，只写测试向量，不调用模型、不访问业务库。 */
@EnabledIfSystemProperty(named = "teamdocs.test.milvus", matches = "true")
class MilvusContainerIntegrationTest {
    /** 验证真实 Milvus 的建表、写入、空间过滤、版本零和删除协议。 */
    @Test
    void realMilvusPreservesSpaceBoundariesAndVersionZero() {
        try (Network network = Network.newNetwork();
             GenericContainer<?> etcd = new GenericContainer<>("quay.io/coreos/etcd:v3.5.25")
                     .withNetwork(network).withNetworkAliases("etcd")
                     .withEnv("ETCD_AUTO_COMPACTION_MODE", "revision")
                     .withEnv("ETCD_AUTO_COMPACTION_RETENTION", "1000")
                     .withCommand("etcd", "--advertise-client-urls=http://etcd:2379",
                             "--listen-client-urls=http://0.0.0.0:2379", "--data-dir=/etcd")
                     .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(256L * 1024 * 1024))
                     .waitingFor(Wait.forLogMessage(".*ready to serve client requests.*", 1));
             GenericContainer<?> storage = new GenericContainer<>(System.getProperty("teamdocs.test.minio-image", "ghcr.io/git-creat7/teamdocs/minio@sha256:648817f3b321ec7a2f86c594ba468fa19eff8ee3ac17a07c03acf7a8a35fda33"))
                     .withNetwork(network).withNetworkAliases("storage")
                     .withEnv("MINIO_ROOT_USER", "test-vector-user")
                     .withEnv("MINIO_ROOT_PASSWORD", "test-vector-password")
                     .withCommand("server", "/data", "--console-address", ":9001")
                     .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(384L * 1024 * 1024))
                     .waitingFor(Wait.forLogMessage(".*API:.*", 1));
             GenericContainer<?> milvus = new GenericContainer<>("milvusdb/milvus:v3.0.2")
                     .withNetwork(network)
                     .withEnv("ETCD_ENDPOINTS", "etcd:2379")
                     .withEnv("MINIO_ADDRESS", "storage:9000")
                     .withEnv("MINIO_ACCESS_KEY_ID", "test-vector-user")
                     .withEnv("MINIO_SECRET_ACCESS_KEY", "test-vector-password")
                     .withEnv("MINIO_REGION", "us-east-1")
                     .withEnv("OMP_NUM_THREADS", "2")
                     .withCommand("milvus", "run", "standalone")
                     .withExposedPorts(19530, 9091)
                     .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
                             .withMemory(2L * 1024 * 1024 * 1024)
                             .withPortBindings(
                                     new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", 0), ExposedPort.tcp(19530)),
                                     new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", 0), ExposedPort.tcp(9091))))
                     .waitingFor(Wait.forHttp("/healthz").forPort(9091).forStatusCode(200)
                             .withStartupTimeout(Duration.ofMinutes(3)))) {
            etcd.start();
            storage.start();
            milvus.start();

            MilvusProperties properties = new MilvusProperties();
            properties.setUrl("http://127.0.0.1:" + milvus.getMappedPort(19530));
            properties.setCollection("synthetic_vector_contract");
            properties.setTimeoutSeconds(10);

            EmbeddingProperties embedding = new EmbeddingProperties();
            embedding.setModelName("synthetic-test-model");
            embedding.setDimensions(2);

            MilvusVectorClient client = new MilvusVectorClient(properties, embedding, new RetrievalHttp(new ObjectMapper()));
            client.ensureCollection();
            client.upsert(List.of(chunk(101, 10, 1), chunk(102, 11, 1), chunk(201, 20, 2)),
                    List.of(List.of(1f, 0f), List.of(0f, 1f), List.of(1f, 0f)));

            var hits = client.search(1L, null, List.of(1f, 0f), 6);

            assertEquals(2, hits.size());
            assertEquals(101L, hits.get(0).getChunkId());
            assertTrue(hits.stream().allMatch(hit -> hit.getParseVersion() == 0));
            assertEquals(List.of(102L), client.search(1L, 11L, List.of(1f, 0f), 6)
                    .stream().map(hit -> hit.getChunkId()).toList());

            client.deleteDocument(10L);

            assertEquals(List.of(102L), client.search(1L, null, List.of(1f, 0f), 6)
                    .stream().map(hit -> hit.getChunkId()).toList());
            assertEquals(201L, client.search(2L, null, List.of(1f, 0f), 6).get(0).getChunkId());
        }
    }

    /** 构造固定版本的合成分块，数据只进入本测试创建的容器。 */
    private ChunkHitVO chunk(long id, long documentId, long spaceId) {
        ChunkHitVO chunk = new ChunkHitVO();
        chunk.setChunkId(id);
        chunk.setDocumentId(documentId);
        chunk.setSpaceId(spaceId);
        chunk.setParseVersion(0);
        chunk.setExcerpt("合成资料，不是业务数据");

        return chunk;
    }
}
