package asia.creat.teamdocsbackend.retrieval;

import asia.creat.mapper.DocumentContentMapper;
import asia.creat.service.impl.DocumentChunkQueryServiceImpl;
import asia.creat.vo.ChunkHitVO;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 固定样例跑在与 docker-compose.yaml 同参数的 MySQL 上，只测检索，不调用模型。
 */
@Testcontainers
class DocumentChunkQuerySqlTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withCommand("--ngram-token-size=2", "--innodb-ft-enable-stopword=OFF",
                    "--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci");

    private static SqlSession session;
    private static DocumentContentMapper mapper;

    @BeforeAll
    static void loadSamples() throws Exception {
        try (Connection connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             Statement statement = connection.createStatement()) {
            for (String script : List.of("initSpace.sql", "initDocument.sql", "fulltext_index.sql", "document_content.sql")) {
                ScriptUtils.executeSqlScript(connection, new EncodedResource(
                        new FileSystemResource("../sql/" + script), StandardCharsets.UTF_8));
            }
            statement.execute("INSERT INTO space (id, name, owner_id, deleted) VALUES (1, '检索', 7, 0), (2, '别的空间', 7, 0), (3, '已删空间', 7, 1)");
            statement.execute("""
                    INSERT INTO document (id, space_id, name, file_path, upload_by, deleted, parse_status, parse_version) VALUES
                    (10, 1, '上线手册.md', 'k/10', 7, 0, 'READY', 3),
                    (11, 2, '别的空间.md', 'k/11', 7, 0, 'READY', 1),
                    (12, 1, '已删除.md', 'k/12', 7, 1, 'READY', 1),
                    (13, 1, '重新解析中.md', 'k/13', 7, 0, 'PENDING', 2),
                    (14, 3, '空间已删.md', 'k/14', 7, 0, 'READY', 1),
                    (15, 1, '会议室.md', 'k/15', 7, 0, 'READY', 1),
                    (16, 1, '部署说明.md', 'k/16', 7, 0, 'READY', 1)""");
            statement.execute("""
                    INSERT INTO document_content (document_id, space_id, chunk_index, content, page_number, char_start, char_end) VALUES
                    (10, 1, 0, '上线检查清单：先备份数据库，再执行迁移脚本。', 2, 0, 22),
                    (10, 1, 1, '回滚步骤：从备份恢复后重启服务。', 2, 22, 38),
                    (11, 2, 0, '上线检查清单：先备份数据库。', NULL, 0, 14),
                    (12, 1, 0, '上线检查清单：先备份数据库。', NULL, 0, 14),
                    (13, 1, 0, '上线检查清单：先备份数据库。', NULL, 0, 14),
                    (14, 3, 0, '上线检查清单：先备份数据库。', NULL, 0, 14),
                    (15, 1, 0, '会议室预约不要写入上线步骤。', NULL, 0, 14),
                    (16, 1, 0, '部署前确认 API 网关限流，zip 压缩包不超过 10MB。', NULL, 0, 31)""");
        }
        Configuration configuration = new Configuration(new Environment("test", new JdbcTransactionFactory(),
                new UnpooledDataSource("com.mysql.cj.jdbc.Driver", MYSQL.getJdbcUrl(), "root", MYSQL.getPassword())));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(DocumentContentMapper.class);
        session = new SqlSessionFactoryBuilder().build(configuration).openSession();
        mapper = session.getMapper(DocumentContentMapper.class);
    }

    @AfterAll
    static void closeSession() {
        session.close();
    }

    @Test
    void knownAnswerOnlyComesFromReadableDocumentsInTheSpace() {
        List<ChunkHitVO> hits = search(1L, "上线检查");

        assertEquals(List.of("10#0"), keys(hits));
        assertEquals(3, hits.get(0).getParseVersion());
        assertEquals("上线手册.md", hits.get(0).getDocumentName());
        assertEquals(2, hits.get(0).getPageNumber());
        assertEquals(22, hits.get(0).getCharEnd());
        assertTrue(hits.get(0).getExcerpt().contains("备份数据库"));
        assertEquals(List.of("11#0"), keys(search(2L, "上线检查")));
        assertTrue(search(3L, "上线检查").isEmpty());
    }

    @Test
    void separateTermsMatchAcrossChunks() {
        // 测试容器的 MySQL 配置 table_open_cache=4，表统计滞后时相关度全为 0，这里不校验顺序
        assertEquals(Set.of("10#0", "10#1"), Set.copyOf(keys(search(1L, "回滚 备份"))));
    }

    @Test
    void englishAbbreviationIgnoresCaseAndStopwordLettersStillMatch() {
        assertEquals(List.of("16#0"), keys(search(1L, "api")));
        // 默认停用词表有 i，没关掉时 zi、ip 都进不了索引
        assertEquals(List.of("16#0"), keys(search(1L, "zip")));
    }

    @Test
    void singleCharacterFindsNothingWithBigramIndex() {
        assertTrue(mapper.searchChunks(1L, "\"备\"", 6).isEmpty());
    }

    @Test
    void readPagesOnlyCurrentReadyChunksInTheSpace() {
        assertEquals(List.of("10#0"), keys(mapper.readChunks(1L, 10L, 0, 1)));
        assertEquals(List.of("10#1"), keys(mapper.readChunks(1L, 10L, 1, 5)));
        for (long documentId : new long[]{11L, 12L, 13L}) {
            assertTrue(mapper.readChunks(1L, documentId, 0, 5).isEmpty());
        }
        assertTrue(mapper.readChunks(3L, 14L, 0, 5).isEmpty());
    }

    @Test
    void citationNeedsSameVersionDocumentAndSpace() {
        Long chunkId = mapper.readChunks(1L, 10L, 0, 1).get(0).getChunkId();

        assertEquals(3, mapper.findReadableChunk(1L, 10L, chunkId, 3).getParseVersion());
        assertNull(mapper.findReadableChunk(1L, 10L, chunkId, 2));
        assertNull(mapper.findReadableChunk(1L, 15L, chunkId, 3));
        assertNull(mapper.findReadableChunk(2L, 10L, chunkId, 3));
    }

    private static List<ChunkHitVO> search(Long spaceId, String keyword) {
        return mapper.searchChunks(spaceId, DocumentChunkQueryServiceImpl.matchQuery(keyword), 6);
    }

    private static List<String> keys(List<ChunkHitVO> hits) {
        return hits.stream().map(hit -> hit.getDocumentId() + "#" + hit.getChunkIndex()).toList();
    }
}
