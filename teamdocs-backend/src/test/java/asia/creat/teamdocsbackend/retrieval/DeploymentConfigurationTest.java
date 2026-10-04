package asia.creat.teamdocsbackend.retrieval;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class DeploymentConfigurationTest {
    /** 基础与语义服务共用文件，但新增依赖只按需启动，SQL 仅挂载一次。 */
    @Test
    void composeKeepsSemanticServicesOptionalAndBootstrapSingle() throws Exception {
        Map<?, ?> compose = new Yaml().load(Files.readString(Path.of("../docker-compose.yaml")));
        Map<?, ?> services = (Map<?, ?>) compose.get("services");
        Map<?, ?> mysql = (Map<?, ?>) services.get("mysql");
        var mounts = (java.util.List<?>) mysql.get("volumes");
        assertEquals(1, mounts.stream().filter(value -> value.toString().contains("docker-entrypoint-initdb.d")).count());
        assertTrue(mounts.contains("./sql/init.sql:/docker-entrypoint-initdb.d/init.sql:ro"));
        for (String name : java.util.List.of("milvus", "milvus-etcd", "milvus-storage")) {
            assertEquals(java.util.List.of("semantic"), ((Map<?, ?>) services.get(name)).get("profiles"));
        }
        assertFalse(((Map<?, ?>) services.get("milvus-storage")).containsKey("ports"));
        for (String name : java.util.List.of("backend", "frontend", "elasticsearch")) {
            String image = ((Map<?, ?>) services.get(name)).get("image").toString();
            assertTrue(image.contains("${IMAGE_TAG:?"));
            assertFalse(image.contains(":-latest"));
        }
        assertTrue(((Map<?, ?>) services.get("elasticsearch")).get("image").toString().contains("/elasticsearch:"));
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
        assertEquals(server, ((Map<?, ?>) services.get("milvus-storage")).get("image"));
        assertTrue(server.startsWith("ghcr.io/git-creat7/teamdocs/minio@sha256:"));
        assertTrue(client.contains("ghcr.io/git-creat7/teamdocs/mc@sha256:"));
        assertFalse(composeText.contains("image: minio/minio:"));
        assertFalse(client.contains(":latest"));

        String workflow = Files.readString(Path.of("../.github/workflows/ci.yml"));
        assertFalse(workflow.contains("context: ./docker/minio"));
        assertFalse(workflow.contains("Build pinned MinIO"));
        String mirror = Files.readString(Path.of("../.github/workflows/mirror-minio.yml"));
        assertTrue(mirror.contains("workflow_dispatch:"));
        assertTrue(mirror.contains("--prefer-index=false"));
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
                "agent_tool_call", "agent_model_call", "document_vector_task"), tables);
        assertTrue(sql.indexOf("CREATE TABLE document (") < sql.indexOf("ALTER TABLE document"));
        assertTrue(sql.indexOf("SET PERSIST innodb_ft_enable_stopword") < sql.indexOf("CREATE TABLE document_content"));
    }
}
