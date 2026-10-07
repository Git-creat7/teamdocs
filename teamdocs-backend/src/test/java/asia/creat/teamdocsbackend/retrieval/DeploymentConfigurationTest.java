package asia.creat.teamdocsbackend.retrieval;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import static org.junit.jupiter.api.Assertions.*;

class DeploymentConfigurationTest {
    /** 启动入口按用途拆分，服务名和持久卷保持不变。 */
    @Test
    void composeKeepsSemanticServicesOptionalAndBootstrapSingle() throws Exception {
        Map<?, ?> base = new Yaml().load(Files.readString(Path.of("../docker-compose.yaml")));
        Map<?, ?> dev = new Yaml().load(Files.readString(Path.of("../docker-compose.dev.yaml")));
        Map<?, ?> semantic = new Yaml().load(Files.readString(Path.of("../docker-compose.semantic.yaml")));
        Map<?, ?> services = (Map<?, ?>) base.get("services");
        Map<?, ?> devServices = (Map<?, ?>) dev.get("services");
        Map<?, ?> semanticServices = (Map<?, ?>) semantic.get("services");
        assertEquals("teamdocs", base.get("name"));
        assertEquals(base.get("name"), dev.get("name"));
        assertEquals(base.get("name"), semantic.get("name"));
        assertEquals(Set.of("mysql", "redis", "minio", "minio-init", "elasticsearch", "backend", "frontend"), services.keySet());
        assertEquals(Set.of("elasticsearch", "milvus", "milvus-etcd", "milvus-storage"), devServices.keySet());
        assertEquals(Set.of("milvus", "milvus-etcd", "milvus-storage"), semanticServices.keySet());
        var mounts = (List<?>) ((Map<?, ?>) services.get("mysql")).get("volumes");
        assertEquals(1, mounts.stream().filter(value -> value.toString().contains("docker-entrypoint-initdb.d")).count());
        assertTrue(mounts.contains("./sql/init.sql:/docker-entrypoint-initdb.d/init.sql:ro"));
        for (String name : List.of("mysql", "redis", "elasticsearch")) {
            assertFalse(((Map<?, ?>) services.get(name)).containsKey("ports"));
        }
        var esPorts = (List<?>) ((Map<?, ?>) devServices.get("elasticsearch")).get("ports");
        assertTrue(esPorts.stream().allMatch(port -> port.toString().startsWith("127.0.0.1:")));
        for (String name : List.of("milvus", "milvus-etcd", "milvus-storage")) {
            var extension = (Map<?, ?>) ((Map<?, ?>) devServices.get(name)).get("extends");
            assertEquals("./docker-compose.semantic.yaml", extension.get("file"));
            assertEquals(name, extension.get("service"));
        }
        assertEquals(Set.of("elasticsearch_data", "milvus_data", "milvus_etcd_data", "milvus_storage_data"),
                ((Map<?, ?>) dev.get("volumes")).keySet());
        for (Object value : semanticServices.values()) assertFalse(((Map<?, ?>) value).containsKey("profiles"));
        assertFalse(((Map<?, ?>) semanticServices.get("milvus-storage")).containsKey("ports"));
        assertFalse(((Map<?, ?>) semanticServices.get("milvus-etcd")).containsKey("ports"));
        assertEquals("teamdocs/elasticsearch:local", ((Map<?, ?>) devServices.get("elasticsearch")).get("image"));
        for (String name : List.of("backend", "frontend", "elasticsearch")) {
            String image = ((Map<?, ?>) services.get(name)).get("image").toString();
            assertTrue(image.contains("${IMAGE_TAG:?"));
            assertFalse(image.contains(":-latest"));
        }
        var environment = (Map<?, ?>) ((Map<?, ?>) services.get("backend")).get("environment");
        assertEquals("mysql", environment.get("DB_HOST"));
        assertEquals(3306, environment.get("DB_PORT"));
        assertEquals("http://elasticsearch:9200", environment.get("ES_URL"));
        assertEquals(Set.of("mysql_data", "redis_data", "minio_data", "elasticsearch_data"), ((Map<?, ?>) base.get("volumes")).keySet());
        assertEquals(Set.of("milvus_data", "milvus_etcd_data", "milvus_storage_data"), ((Map<?, ?>) semantic.get("volumes")).keySet());
    }

    /** 每次提交只跑一轮普通测试，检索评估需要手动勾选。 */
    @Test
    void workflowSeparatesOrdinaryTestsFromManualEvaluation() throws Exception {
        String source = Files.readString(Path.of("../.github/workflows/ci.yml"));
        Map<?, ?> workflow = new Yaml().load(source);

        assertTrue(workflow.containsKey("jobs"));
        assertTrue(source.contains("'-Dtest=*,!AgentEvaluationTest' -Dteamdocs.test.vector-sql=true test"));
        assertTrue(source.contains("if: github.event_name == 'workflow_dispatch' && inputs.run_retrieval_evaluation"));
        assertFalse(source.contains("-Dtest=VectorIndexSqlTest -Dteamdocs.test.vector-sql=true test"));
        assertFalse(source.contains("build-init-sql.py"));
    }

    /** CI 与部署使用固定镜像摘要；手动同步独立于日常应用发布。 */
    @Test
    void minioMirrorsArePinnedAndSeparateFromApplicationBuilds() throws Exception {
        String composeText = Files.readString(Path.of("../docker-compose.yaml"));
        Map<?, ?> services = (Map<?, ?>) new Yaml().loadAs(composeText, Map.class).get("services");
        String server = ((Map<?, ?>) services.get("minio")).get("image").toString();
        String client = ((Map<?, ?>) services.get("minio-init")).get("image").toString();

        Map<?, ?> semantic = new Yaml().load(Files.readString(Path.of("../docker-compose.semantic.yaml")));
        var semanticServices = (Map<?, ?>) semantic.get("services");
        assertEquals(server, ((Map<?, ?>) semanticServices.get("milvus-storage")).get("image"));
        assertTrue(server.startsWith("ghcr.io/git-creat7/teamdocs/minio@sha256:"));
        assertTrue(client.contains("ghcr.io/git-creat7/teamdocs/mc@sha256:"));
        assertFalse(composeText.contains("image: minio/minio:"));
        assertFalse(client.contains(":latest"));

        String workflow = Files.readString(Path.of("../.github/workflows/ci.yml"));

        assertFalse(workflow.contains("context: ./docker/minio"));
        assertFalse(workflow.contains("Build pinned MinIO"));

        String mirror = Files.readString(Path.of("../.github/workflows/mirror-minio.yml"));

        assertTrue(mirror.contains("workflow_dispatch:"));
        assertTrue(mirror.contains("--preserve-digests"));
        assertTrue(mirror.contains("sha256sum --check"));
        assertTrue(mirror.contains("packages: write"));
        assertTrue(mirror.contains(server));
        assertFalse(mirror.contains(":latest"));
    }

    /** 单一初始化入口包含全部表，全文索引依赖顺序不被打乱。 */
    @Test
    void mergedBootstrapContainsAllTablesInDependencyOrder() throws Exception {
        String sql = Files.readString(Path.of("../sql/init.sql"));
        var matcher = Pattern.compile("(?i)CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(\\w+)").matcher(sql);
        Set<String> tables = new HashSet<>();

        while (matcher.find()) assertTrue(tables.add(matcher.group(1)));

        assertEquals(Set.of("user", "space", "space_member", "folder", "document", "tag", "document_tag",
                "comment", "operation_log", "document_content", "agent_session", "agent_run", "agent_message",
                "agent_tool_call", "agent_model_call", "document_vector_task", "user_memory", "user_memory_job", "user_model_config", "agent_answer_feedback", "agent_attachment"), tables);
        assertTrue(sql.indexOf("CREATE TABLE document (") < sql.indexOf("ALTER TABLE document"));
        assertTrue(sql.indexOf("SET PERSIST innodb_ft_enable_stopword") < sql.indexOf("CREATE TABLE document_content"));
    }
}
