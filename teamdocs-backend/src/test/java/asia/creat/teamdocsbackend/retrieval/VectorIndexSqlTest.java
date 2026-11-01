package asia.creat.teamdocsbackend.retrieval;

import asia.creat.mapper.VectorIndexMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.MySQLContainer;
import static org.junit.jupiter.api.Assertions.*;

/** 只有显式开启才启动隔离 MySQL，不连接业务数据库。 */
@EnabledIfSystemProperty(named = "teamdocs.test.vector-sql", matches = "true")
class VectorIndexSqlTest {
    private static MySQLContainer<?> mysql;
    private static DriverManagerDataSource dataSource;
    private static SqlSessionFactory sessions;

    /** 创建独立 MySQL 和最小业务夹具。 */
    @BeforeAll
    static void startDatabase() throws Exception {
        mysql = new MySQLContainer<>("mysql:8.4");
        mysql.start();
        dataSource = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE space(id BIGINT PRIMARY KEY,deleted INT NOT NULL)");
        jdbc.execute("CREATE TABLE document(id BIGINT PRIMARY KEY,space_id BIGINT,parse_version INT,parse_status VARCHAR(16),deleted INT)");

        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("../sql/vector_index.sql"));
        }

        MybatisConfiguration config = new MybatisConfiguration(new Environment("vector-test", new JdbcTransactionFactory(), dataSource));
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(VectorIndexMapper.class);
        sessions = new MybatisSqlSessionFactoryBuilder().build(config);
    }

    /** 仅停止本测试创建的容器。 */
    @AfterAll
    static void stopDatabase() {
        if (mysql != null) mysql.stop();
    }

    /** 重置隔离测试表，不使用任何开发数据库地址。 */
    @BeforeEach
    void resetRows() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM document_vector_task");
        jdbc.update("DELETE FROM document");
        jdbc.update("DELETE FROM space");
        jdbc.update("INSERT INTO space VALUES(1,0)");
        jdbc.update("INSERT INTO document VALUES(10,1,1,'READY',0)");
    }

    /** 待办参与事务，回滚不会遗留需要索引的记录。 */
    @Test
    void enqueueRollsBackWithBusinessTransaction() {
        try (SqlSession session = sessions.openSession(false)) {
            VectorIndexMapper mapper = session.getMapper(VectorIndexMapper.class);

            assertTrue(mapper.snapshot(10L).isReady());
            mapper.enqueue(10L, "v1");
            session.rollback();

            assertNull(mapper.task(10L));
        }
    }

    /** 相同目标保持 DONE，新目标递增代次，旧完成通知不能覆盖新待办。 */
    @Test
    void unchangedTargetDoesNotRequeueAndOldGenerationCannotComplete() {
        try (SqlSession session = sessions.openSession(true)) {
            VectorIndexMapper mapper = session.getMapper(VectorIndexMapper.class);
            mapper.enqueue(10L, "v1");
            mapper.complete(10L, 1);
            mapper.enqueue(10L, "v1");

            assertEquals("DONE", mapper.task(10L).getState());

            mapper.enqueue(10L, "v2");
            mapper.complete(10L, 1);
            mapper.fail(10L, 1, 100);

            var current = mapper.task(10L);

            assertEquals(2, current.getGeneration());
            assertEquals("PENDING", current.getState());
            assertEquals(0, current.getAttempts());
        }
    }

    /** 尝试次数与退避持久化，超过三次后不能再次领取。 */
    @Test
    void persistedAttemptsAreBoundedAndReservationIsConditional() {
        try (SqlSession session = sessions.openSession(true)) {
            VectorIndexMapper mapper = session.getMapper(VectorIndexMapper.class);
            mapper.enqueue(10L, "v1");

            assertTrue(mapper.reserve(10L, 1, 0, 100));
            assertFalse(mapper.reserve(10L, 1, 0, 100));

            mapper.fail(10L, 1, 10);

            assertTrue(mapper.reserve(10L, 1, 10, 100));
            mapper.fail(10L, 1, 20);

            assertTrue(mapper.reserve(10L, 1, 20, 100));
            mapper.fail(10L, 1, 30);

            assertEquals("FAILED", mapper.task(10L).getState());
            assertFalse(mapper.reserve(10L, 1, 1000, 2000));
            assertNull(mapper.next(1000));
        }
    }

    /** 彻底删除不会删除清理待办，补扫可以找到遗留向量。 */
    @Test
    void deletedDocumentRemainsDiscoverableForCleanup() {
        try (SqlSession session = sessions.openSession(true)) {
            VectorIndexMapper mapper = session.getMapper(VectorIndexMapper.class);
            mapper.enqueue(10L, "v1");
            new JdbcTemplate(dataSource).update("DELETE FROM document WHERE id=10");
            session.clearCache();

            assertNull(mapper.snapshot(10L));
            assertEquals(List.of(10L), mapper.deletedDocumentIds());

            mapper.enqueue(10L, "deleted");

            assertTrue(mapper.deletedDocumentIds().isEmpty());
        }
    }

    /** 租约抢占、条件原子切换与清理挂起状态流转。 */
    @Test
    void leaseReservationAndAtomicSwitchWithVersion() {
        try (SqlSession session = sessions.openSession(true)) {
            VectorIndexMapper mapper = session.getMapper(VectorIndexMapper.class);
            mapper.enqueue(10L, "v2-sig");

            var task = mapper.task(10L);
            assertNotNull(task);
            assertEquals(1, task.getTargetParseVersion());

            assertTrue(mapper.reserve(10L, 1, "token-1", 1000, 2000));
            assertFalse(mapper.reserve(10L, 1, "token-2", 1500, 2500));

            assertTrue(mapper.verifyCurrent(10L, 1, "token-1", 1500));
            assertFalse(mapper.verifyCurrent(10L, 1, "wrong-token", 1500));
            assertFalse(mapper.verifyCurrent(10L, 1, "token-1", 2500));

            assertTrue(mapper.completeAndSwitch(10L, 1, "token-1", 1800, 1, 10));

            task = mapper.task(10L);
            assertEquals("DONE", task.getState());
            assertEquals(1L, task.getActiveGeneration());
            assertEquals(1, task.getActiveParseVersion());
            assertEquals(10, task.getActiveChunkCount());
            assertEquals(1, task.getCleanupPending());

            var pending = mapper.pendingCleanupTasks();
            assertEquals(1, pending.size());
            assertEquals(10L, pending.get(0).getDocumentId());

            assertTrue(mapper.clearCleanupPending(10L, 1L));
            assertTrue(mapper.pendingCleanupTasks().isEmpty());
        }
    }

    /** 租约过期后新 Worker 抢占，旧 Worker 切换与失败均被拦截。 */
    @Test
    void expiredLeaseAllowsNewWorkerToClaim() {
        try (SqlSession session = sessions.openSession(true)) {
            VectorIndexMapper mapper = session.getMapper(VectorIndexMapper.class);
            mapper.enqueue(10L, "v2-sig");

            assertTrue(mapper.reserve(10L, 1, "token-1", 1000, 2000));

            assertTrue(mapper.reserve(10L, 1, "token-2", 2001, 3000));

            assertFalse(mapper.completeAndSwitch(10L, 1, "token-1", 2005, 1, 5));
            assertFalse(mapper.fail(10L, 1, "token-1", 2005, 4000));

            var task = mapper.task(10L);
            assertEquals("PENDING", task.getState());
            assertEquals("token-2", task.getClaimToken());

            assertTrue(mapper.completeAndSwitch(10L, 1, "token-2", 2500, 1, "teamdocs_chunks_d10_g1", 5));
            assertEquals("DONE", mapper.task(10L).getState());
            assertEquals("teamdocs_chunks_d10_g1", mapper.task(10L).getActiveCollection());
        }
    }

    /** 租约过期但尚未被其他 Worker 重新领取时，旧 Worker 迟到失败回写与切换回写必须失败，且校验 target_parse_version。 */
    @Test
    void expiredLeaseUnclaimedTaskRejectsLateWorkerFailAndSwitch() {
        try (SqlSession session = sessions.openSession(true)) {
            VectorIndexMapper mapper = session.getMapper(VectorIndexMapper.class);
            mapper.enqueue(10L, "v1-sig");

            assertTrue(mapper.reserve(10L, 1, "token-1", 1000, 2000));

            // 租约内 target_parse_version 不匹配时切换必须失败
            assertFalse(mapper.completeAndSwitch(10L, 1, "token-1", 1500, 99, "teamdocs_chunks_d10_g1", 5));

            // 时间来到 2001（租约已于 2000 到期），且此时尚无其他 Worker 重新领取（claim_token 仍为 token-1）
            assertFalse(mapper.fail(10L, 1, "token-1", 2001, 9999));
            assertFalse(mapper.completeAndSwitch(10L, 1, "token-1", 2001, 1, "teamdocs_chunks_d10_g1", 5));

            var task = mapper.task(10L);
            assertEquals("PENDING", task.getState());
            assertEquals("token-1", task.getClaimToken());
            assertEquals(2000L, task.getNextAttemptMs());
            assertNull(task.getErrorCode());
            assertEquals(0L, task.getActiveGeneration());
        }
    }

    /** 历史数据库升级 SQL 补齐 parse_version、active_generation/active_parse_version/active_collection，并支持重复执行。 */
    @Test
    void historicalDatabaseMigrationAndIdempotentRepeatExecution() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS document_vector_task");
        jdbc.execute("DROP TABLE IF EXISTS document_content");
        jdbc.execute("DROP TABLE IF EXISTS document");

        // 构造存量旧版表结构（无 summary 相关列、无 document_content.parse_version/heading、无新向量任务列）
        jdbc.execute("""
                CREATE TABLE document (
                    id BIGINT PRIMARY KEY,
                    space_id BIGINT NOT NULL,
                    parse_version INT NOT NULL DEFAULT 1,
                    parse_status VARCHAR(16) NOT NULL DEFAULT 'READY',
                    deleted INT NOT NULL DEFAULT 0
                )
                """);
        jdbc.execute("""
                CREATE TABLE document_content (
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    document_id BIGINT NOT NULL,
                    space_id BIGINT NOT NULL,
                    chunk_index INT NOT NULL,
                    content MEDIUMTEXT NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE document_vector_task (
                    document_id BIGINT NOT NULL PRIMARY KEY,
                    generation BIGINT NOT NULL DEFAULT 1,
                    target_signature VARCHAR(192) NOT NULL,
                    state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
                    attempts INT NOT NULL DEFAULT 0,
                    next_attempt_ms BIGINT NOT NULL DEFAULT 0,
                    error_code VARCHAR(64) NULL,
                    INDEX idx_vector_task_pending (state, next_attempt_ms)
                )
                """);

        // 写入存量历史数据：文档 10（parse_version=3，已完成向量化 DONE, generation=2），文档 20（PENDING）
        jdbc.update("INSERT INTO document(id, space_id, parse_version, parse_status, deleted) VALUES (10, 1, 3, 'READY', 0), (20, 1, 2, 'READY', 0)");
        jdbc.update("INSERT INTO document_content(document_id, space_id, chunk_index, content) VALUES (10, 1, 0, '旧分块A'), (10, 1, 1, '旧分块B'), (20, 1, 0, '旧分块C')");
        jdbc.update("INSERT INTO document_vector_task(document_id, generation, target_signature, state, attempts, next_attempt_ms) VALUES (10, 2, 'legacy-sig-10', 'DONE', 1, 0), (20, 1, 'legacy-sig-20', 'PENDING', 0, 0)");

        // 连续执行两次升级脚本，验证存量升级与重复执行幂等保护
        String summaryAndHeadingSql = """
                SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'document' AND COLUMN_NAME = 'summary');
                SET @ddl = IF(@col_exists = 0, 'ALTER TABLE document ADD COLUMN summary TEXT NULL', 'SELECT 1');
                PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

                SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'document' AND COLUMN_NAME = 'summary_source');
                SET @ddl = IF(@col_exists = 0, 'ALTER TABLE document ADD COLUMN summary_source VARCHAR(32) NULL', 'SELECT 1');
                PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

                SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'document' AND COLUMN_NAME = 'summary_state');
                SET @ddl = IF(@col_exists = 0, 'ALTER TABLE document ADD COLUMN summary_state VARCHAR(32) NOT NULL DEFAULT \\'NONE\\'', 'SELECT 1');
                PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

                SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'document' AND COLUMN_NAME = 'summary_error');
                SET @ddl = IF(@col_exists = 0, 'ALTER TABLE document ADD COLUMN summary_error VARCHAR(255) NULL', 'SELECT 1');
                PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

                SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'document' AND COLUMN_NAME = 'summary_worker_id');
                SET @ddl = IF(@col_exists = 0, 'ALTER TABLE document ADD COLUMN summary_worker_id VARCHAR(64) NULL', 'SELECT 1');
                PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

                SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'document' AND COLUMN_NAME = 'summary_lease_until');
                SET @ddl = IF(@col_exists = 0, 'ALTER TABLE document ADD COLUMN summary_lease_until BIGINT NULL', 'SELECT 1');
                PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

                SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'document' AND COLUMN_NAME = 'summary_version');
                SET @ddl = IF(@col_exists = 0, 'ALTER TABLE document ADD COLUMN summary_version INT NOT NULL DEFAULT 0', 'SELECT 1');
                PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

                SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'document' AND COLUMN_NAME = 'summary_updated_at');
                SET @ddl = IF(@col_exists = 0, 'ALTER TABLE document ADD COLUMN summary_updated_at DATETIME NULL', 'SELECT 1');
                PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

                SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'document' AND COLUMN_NAME = 'keywords');
                SET @ddl = IF(@col_exists = 0, 'ALTER TABLE document ADD COLUMN keywords VARCHAR(500) NULL', 'SELECT 1');
                PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

                SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'document_content' AND COLUMN_NAME = 'parse_version');
                SET @ddl = IF(@col_exists = 0, 'ALTER TABLE document_content ADD COLUMN parse_version INT DEFAULT NULL AFTER chunk_index', 'SELECT 1');
                PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

                SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'document_content' AND COLUMN_NAME = 'heading');
                SET @ddl = IF(@col_exists = 0, 'ALTER TABLE document_content ADD COLUMN heading VARCHAR(255) NULL AFTER parse_version', 'SELECT 1');
                PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

                UPDATE document_content c
                INNER JOIN document d ON d.id = c.document_id
                SET c.parse_version = d.parse_version
                WHERE c.parse_version IS NULL OR (d.parse_version IS NOT NULL AND c.parse_version <> d.parse_version);

                UPDATE document
                SET summary_version = COALESCE(parse_version, 1),
                    summary_state = CASE WHEN summary IS NULL AND parse_status = 'READY' AND deleted = 0 THEN 'PENDING' ELSE summary_state END
                WHERE summary_version = 0 AND parse_version IS NOT NULL;
                """;

        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ByteArrayResource(summaryAndHeadingSql.getBytes(StandardCharsets.UTF_8)));
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("../sql/vector_index.sql"));
            // 第二次重复执行不报错
            ScriptUtils.executeSqlScript(connection, new ByteArrayResource(summaryAndHeadingSql.getBytes(StandardCharsets.UTF_8)));
            ScriptUtils.executeSqlScript(connection, new FileSystemResource("../sql/vector_index.sql"));
        }

        // 校验 document_content.parse_version 历史回填
        List<Integer> doc10ChunkVersions = jdbc.queryForList("SELECT parse_version FROM document_content WHERE document_id=10 ORDER BY chunk_index", Integer.class);
        assertEquals(List.of(3, 3), doc10ChunkVersions);
        Integer doc20ChunkVersion = jdbc.queryForObject("SELECT parse_version FROM document_content WHERE document_id=20", Integer.class);
        assertEquals(2, doc20ChunkVersion);

        // 校验 document_vector_task 历史 DONE 任务与 PENDING 任务的初始化
        try (SqlSession session = sessions.openSession(true)) {
            VectorIndexMapper mapper = session.getMapper(VectorIndexMapper.class);
            var doneTask = mapper.task(10L);
            assertEquals("DONE", doneTask.getState());
            assertEquals(2L, doneTask.getActiveGeneration());
            assertEquals(3, doneTask.getActiveParseVersion());
            assertEquals(3, doneTask.getTargetParseVersion());
            assertEquals("teamdocs_qwen3_8b_d1024_v1", doneTask.getActiveCollection());
            assertEquals(2, doneTask.getActiveChunkCount());

            var pendingTask = mapper.task(20L);
            assertEquals("PENDING", pendingTask.getState());
            assertEquals(0L, pendingTask.getActiveGeneration());
            assertNull(pendingTask.getActiveParseVersion());
            assertEquals(2, pendingTask.getTargetParseVersion());
        }
    }
}
