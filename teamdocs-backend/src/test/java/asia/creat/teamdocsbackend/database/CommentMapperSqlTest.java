package asia.creat.teamdocsbackend.database;

import asia.creat.entity.Comment;
import asia.creat.mapper.CommentMapper;
import asia.creat.vo.CommentVO;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.*;

/** 评论 Agent 查询依赖真实 MySQL 验证过滤、排序与 JOIN 分页计数。 */
@Testcontainers
class CommentMapperSqlTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    private static DriverManagerDataSource dataSource;
    private static SqlSessionFactory sessions;

    @BeforeAll
    static void startDatabase() {
        dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        jdbc.execute("CREATE TABLE user(id BIGINT PRIMARY KEY, username VARCHAR(64) NOT NULL)");
        jdbc.execute("""
                CREATE TABLE comment(
                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                    document_id BIGINT NOT NULL,
                    user_id BIGINT NOT NULL,
                    content TEXT NOT NULL,
                    reply_to_id BIGINT NULL,
                    deleted TINYINT NOT NULL DEFAULT 0,
                    created_at DATETIME NOT NULL,
                    KEY idx_document_created (document_id, created_at, id)
                )
                """);
        jdbc.update("INSERT INTO user VALUES (1, 'alice'), (2, 'bob')");
        jdbc.update("""
                INSERT INTO comment(id, document_id, user_id, content, reply_to_id, deleted, created_at) VALUES
                    (1, 10, 1, '旧评论', NULL, 0, '2026-11-01 10:00:00'),
                    (2, 10, 2, '回复已删除评论', 3, 0, '2026-11-01 11:00:00'),
                    (3, 10, 1, '已删除评论', NULL, 1, '2026-11-01 12:00:00'),
                    (4, 10, 2, '最新评论', NULL, 0, '2026-11-01 13:00:00'),
                    (5, 11, 1, '其他文档', NULL, 0, '2026-11-01 14:00:00')
                """);

        MybatisConfiguration config = new MybatisConfiguration(
                new Environment("comment-test", new JdbcTransactionFactory(), dataSource));
        config.setMapUnderscoreToCamelCase(true);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(config, ""), Comment.class);

        MybatisPlusInterceptor pagination = new MybatisPlusInterceptor();
        pagination.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        config.addInterceptor(pagination);
        config.addMapper(CommentMapper.class);
        sessions = new MybatisSqlSessionFactoryBuilder().build(config);
    }

    @AfterAll
    static void stopDatabase() {
        if (MYSQL != null) MYSQL.stop();
    }

    /** 过滤已删除评论，按时间倒序，JOIN 分页总数正确，且不泄露已删除父评论作者。 */
    @Test
    void filtersDeletedCommentsAndPagesNewestFirst() {
        try (SqlSession session = sessions.openSession(true)) {
            CommentMapper mapper = session.getMapper(CommentMapper.class);
            IPage<CommentVO> first = mapper.listForAgent(new Page<>(1, 2), 10L);

            assertEquals(List.of(4L, 2L), first.getRecords().stream().map(CommentVO::getId).toList());
            assertEquals(3, first.getTotal());
            assertEquals(2, first.getPages());
            assertNull(first.getRecords().get(1).getReplyToUserName());

            IPage<CommentVO> second = mapper.listForAgent(new Page<>(2, 2), 10L);

            assertEquals(List.of(1L), second.getRecords().stream().map(CommentVO::getId).toList());
            assertEquals(3, second.getTotal());
        }
    }

    /** 评论面板查询保持原有升序和已删除占位行语义。 */
    @Test
    void uiQueryKeepsAscendingOrderAndDeletedPlaceholder() {
        try (SqlSession session = sessions.openSession(true)) {
            CommentMapper mapper = session.getMapper(CommentMapper.class);
            IPage<CommentVO> page = mapper.listByDocumentId(new Page<>(1, 10), 10L);

            assertEquals(List.of(1L, 2L, 3L, 4L), page.getRecords().stream().map(CommentVO::getId).toList());
            assertNull(page.getRecords().get(2).getContent());
        }
    }
}
