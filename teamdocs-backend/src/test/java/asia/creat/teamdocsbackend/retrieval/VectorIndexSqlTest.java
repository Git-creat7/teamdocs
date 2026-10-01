package asia.creat.teamdocsbackend.retrieval;

import asia.creat.mapper.VectorIndexMapper;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
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
        Configuration config = new Configuration(new Environment("vector-test", new JdbcTransactionFactory(), dataSource));
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(VectorIndexMapper.class);
        sessions = new SqlSessionFactoryBuilder().build(config);
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
            assertEquals(1, mapper.reserve(10L, 1, 0, 100));
            assertEquals(0, mapper.reserve(10L, 1, 0, 100));
            mapper.fail(10L, 1, 10);
            assertEquals(1, mapper.reserve(10L, 1, 10, 100));
            mapper.fail(10L, 1, 20);
            assertEquals(1, mapper.reserve(10L, 1, 20, 100));
            mapper.fail(10L, 1, 30);
            assertEquals("FAILED", mapper.task(10L).getState());
            assertEquals(0, mapper.reserve(10L, 1, 1000, 2000));
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
            assertEquals(java.util.List.of(10L), mapper.deletedDocumentIds());
            mapper.enqueue(10L, "deleted");
            assertTrue(mapper.deletedDocumentIds().isEmpty());
        }
    }
}
