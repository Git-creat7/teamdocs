package asia.creat.teamdocsbackend.parse;

import asia.creat.aspect.SpaceRoleAspect;
import asia.creat.common.BucketType;
import asia.creat.common.exception.BusinessException;
import asia.creat.config.MinioProperties;
import asia.creat.config.MpConfig;
import asia.creat.config.ParseProperties;
import asia.creat.entity.Document;
import asia.creat.entity.DocumentContent;
import asia.creat.entity.ParseStatus;
import asia.creat.helper.ResourcePermissionHelper;
import asia.creat.mapper.DocumentMapper;
import asia.creat.parse.DocumentParseWorker;
import asia.creat.parse.DocumentTextExtractor;
import asia.creat.security.LoginUser;
import asia.creat.service.DocumentContentService;
import asia.creat.service.DocumentParseService;
import asia.creat.service.DocumentService;
import asia.creat.service.FileStorageService;
import asia.creat.service.RecentDocumentService;
import asia.creat.service.impl.DocumentContentServiceImpl;
import asia.creat.service.impl.DocumentParseServiceImpl;
import asia.creat.service.impl.DocumentServiceImpl;
import asia.creat.service.impl.MinioFileStorageServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Testcontainers
@SpringJUnitConfig(DocumentLifecycleIntegrationTest.Config.class)
class DocumentLifecycleIntegrationTest {
    private static final LoginUser OWNER = new LoginUser(7L, "owner");

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");
    @Container
    static final GenericContainer<?> MINIO = new GenericContainer<>(DockerImageName.parse(System.getProperty(
            "teamdocs.test.minio-image", "minio/minio:RELEASE.2023-09-20T22-49-55Z")))
            .withEnv("MINIO_ROOT_USER", "test-access-key")
            .withEnv("MINIO_ROOT_PASSWORD", "test-secret-key")
            .withCommand("server", "/data")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));

    @Autowired DocumentService documents;
    @Autowired DocumentParseService parsing;
    @Autowired DocumentContentService content;
    @Autowired DocumentMapper documentMapper;
    @Autowired DataSource dataSource;
    @Autowired ParseProperties properties;
    @MockitoSpyBean FileStorageService storage;

    private JdbcTemplate jdbc;

    @BeforeAll
    static void initialize() throws Exception {
        try (Connection connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword())) {
            for (String script : List.of("initSpace.sql", "initDocument.sql", "fulltext_index.sql", "document_content.sql")) {
                ScriptUtils.executeSqlScript(connection, new EncodedResource(
                        new FileSystemResource("../sql/" + script), StandardCharsets.UTF_8));
            }
        }
        minio().makeBucket(MakeBucketArgs.builder().bucket("parse-test-private").build());
    }

    @BeforeEach
    void prepareSpace() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM document_content");
        jdbc.update("DELETE FROM document");
        jdbc.update("DELETE FROM space_member");
        jdbc.update("DELETE FROM space");
        jdbc.update("INSERT INTO space (id,name,owner_id) VALUES (1,'解析测试',7)");
        jdbc.update("INSERT INTO space_member (space_id,user_id,role) VALUES (1,7,'OWNER'),(1,8,'MEMBER')");
    }

    @Test
    void uploadScanPublishAndReparseKeepDocumentTimestampAndReplaceChunks() throws Exception {
        Long id = upload("notes.md", "上线检查：备份数据库。\n".repeat(150));
        LocalDateTime modifiedAt = documentMapper.selectById(id).getUpdatedAt();
        assertEquals(ParseStatus.PENDING, parsing.getStatus(1L, id, OWNER).getParseStatus());
        worker().scanPending();
        Document ready = documentMapper.selectById(id);
        assertEquals(ParseStatus.READY, ready.getParseStatus());
        assertTrue(ready.getChunkCount() > 1);
        assertEquals(modifiedAt, ready.getUpdatedAt());
        List<Long> originalIds = content.getChunksByDocumentId(id).stream().map(DocumentContent::getId).toList();
        parsing.parseDocument(id);
        assertEquals(originalIds, content.getChunksByDocumentId(id).stream().map(DocumentContent::getId).toList());

        assertThrows(BusinessException.class, () -> parsing.reparse(1L, id, OWNER));
        jdbc.update("UPDATE document SET parse_started_at=DATE_SUB(NOW(), INTERVAL 1 MINUTE),updated_at=updated_at WHERE id=?", id);
        var pending = parsing.reparse(1L, id, OWNER);
        assertEquals(ready.getParseVersion() + 1, pending.getParseVersion());
        assertTrue(content.getChunksByDocumentId(id).isEmpty());
        worker().scanPending();
        assertEquals(ParseStatus.READY, documentMapper.selectById(id).getParseStatus());
        assertEquals(ready.getChunkCount(), chunkCount(id));
        assertEquals(modifiedAt, documentMapper.selectById(id).getUpdatedAt());
        assertNotEquals(originalIds, content.getChunksByDocumentId(id).stream().map(DocumentContent::getId).toList());
        try (InputStream stream = storage.open(BucketType.PRIVATE, ready.getFilePath())) {
            assertTrue(new String(stream.readAllBytes(), StandardCharsets.UTF_8).contains("备份数据库"));
        }
    }

    @Test
    void malformedFileFailsWithoutBreakingOriginalDownload() throws Exception {
        Long id = upload("broken.pdf", "this is not a PDF");
        parsing.parseDocument(id);
        Document failed = documentMapper.selectById(id);
        assertEquals(ParseStatus.FAILED, failed.getParseStatus());
        assertEquals(0, chunkCount(id));
        assertFalse(failed.getParseError().contains(failed.getFilePath()));
        assertNotNull(documents.downloadDocument(1L, id, OWNER));
        try (InputStream stream = storage.open(BucketType.PRIVATE, failed.getFilePath())) {
            assertEquals("this is not a PDF", new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void failedPublicationRollsBackBothDeletedAndInsertedChunks() {
        Long id = upload("notes.txt", "保留原正文");
        parsing.parseDocument(id);
        Document ready = documentMapper.selectById(id);
        List<DocumentContent> original = content.getChunksByDocumentId(id);
        jdbc.update("UPDATE document SET parse_status='PARSING',updated_at=updated_at WHERE id=?", id);
        assertThrows(RuntimeException.class, () -> content.publishIfParsing(id, 1L, ready.getParseVersion(),
                List.of(chunk("新片段一"), chunk("新片段二"))));
        assertEquals(ParseStatus.PARSING, documentMapper.selectById(id).getParseStatus());
        assertEquals(original.get(0).getId(), jdbc.queryForObject("SELECT id FROM document_content WHERE document_id=?", Long.class, id));
        assertEquals("保留原正文", jdbc.queryForObject("SELECT content FROM document_content WHERE document_id=?", String.class, id));
    }

    @Test
    void timedOutWorkerCannotPublishOverRetriedVersion() throws Exception {
        Long id = upload("notes.txt", "版本竞争测试");
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        blockFirstRead(reading, release);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var oldTask = executor.submit(() -> parsing.parseDocument(id));
            assertTrue(reading.await(10, TimeUnit.SECONDS));
            Document old = documentMapper.selectById(id);
            jdbc.update("UPDATE document SET parse_started_at=DATE_SUB(NOW(), INTERVAL 1 HOUR),updated_at=updated_at WHERE id=?", id);
            // 新 Worker 从数据库恢复超时任务，不依赖旧进程内存。
            worker().failTimedOut();
            assertEquals(ParseStatus.FAILED, documentMapper.selectById(id).getParseStatus());
            var retried = parsing.reparse(1L, id, OWNER);
            assertEquals(old.getParseVersion() + 1, retried.getParseVersion());
            parsing.parseDocument(id);
            List<Long> publishedIds = content.getChunksByDocumentId(id).stream().map(DocumentContent::getId).toList();
            release.countDown();
            oldTask.get(10, TimeUnit.SECONDS);
            assertEquals(ParseStatus.READY, documentMapper.selectById(id).getParseStatus());
            assertEquals(retried.getParseVersion(), documentMapper.selectById(id).getParseVersion());
            assertEquals(publishedIds, content.getChunksByDocumentId(id).stream().map(DocumentContent::getId).toList());
            assertFalse(content.discardIfParsing(id, 1L, old.getParseVersion(), ParseStatus.FAILED, "迟到失败"));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void purgeDuringParsingCannotLeaveOrphanChunks() throws Exception {
        Long id = upload("notes.txt", "删除竞争测试");
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        blockFirstRead(reading, release);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var task = executor.submit(() -> parsing.parseDocument(id));
            assertTrue(reading.await(10, TimeUnit.SECONDS));
            documents.deleteDocument(1L, id, OWNER);
            documents.purgeDocument(1L, id, OWNER);
            release.countDown();
            task.get(10, TimeUnit.SECONDS);
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM document WHERE id=?", Integer.class, id));
            assertEquals(0, chunkCount(id));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void softDeleteHidesChunksAndPurgeAfterPublicationCleansThem() {
        Long id = upload("notes.txt", "删除后不可读");
        parsing.parseDocument(id);
        documents.deleteDocument(1L, id, OWNER);
        assertTrue(content.getChunksByDocumentId(id).isEmpty());
        assertEquals(1, chunkCount(id));
        documents.restoreDocument(1L, id, 0L, OWNER);
        assertEquals(1, content.getChunksByDocumentId(id).size());
        documents.deleteDocument(1L, id, OWNER);
        documents.purgeDocument(1L, id, OWNER);
        assertEquals(0, chunkCount(id));
    }

    @Test
    void checksCurrentMembershipAndCreatorBeforeStatusAndRetry() {
        Long id = upload("notes.txt", "权限测试");
        assertThrows(BusinessException.class, () -> parsing.getStatus(1L, id, new LoginUser(99L, "outsider")));
        assertThrows(BusinessException.class, () -> parsing.reparse(1L, id, new LoginUser(8L, "member")));
        jdbc.update("UPDATE space SET deleted=1 WHERE id=1");
        worker().scanPending();
        parsing.parseDocument(id);
        assertEquals(ParseStatus.PENDING, documentMapper.selectById(id).getParseStatus());
        verify(storage, never()).open(any(), any());
    }

    private Long upload(String name, String text) {
        return documents.upload(1L, 0L, new MockMultipartFile("file", name, "application/octet-stream",
                text.getBytes(StandardCharsets.UTF_8)), OWNER);
    }

    private DocumentParseWorker worker() {
        return new DocumentParseWorker(documentMapper, parsing, properties, Runnable::run);
    }

    private int chunkCount(Long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM document_content WHERE document_id=?", Integer.class, id);
    }

    private static DocumentContent chunk(String text) {
        DocumentContent chunk = new DocumentContent();
        chunk.setChunkIndex(0);
        chunk.setContent(text);
        return chunk;
    }

    private void blockFirstRead(CountDownLatch reading, CountDownLatch release) throws Exception {
        AtomicInteger reads = new AtomicInteger();
        doAnswer(invocation -> {
            InputStream stream = (InputStream) invocation.callRealMethod();
            if (reads.incrementAndGet() == 1) {
                reading.countDown();
                if (!release.await(20, TimeUnit.SECONDS)) {
                    stream.close();
                    throw new IllegalStateException("测试读取等待超时");
                }
            }
            return stream;
        }).when(storage).open(any(), any());
    }

    private static MinioClient minio() {
        return MinioClient.builder().endpoint("http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000))
                .credentials("test-access-key", "test-secret-key").region("us-east-1").build();
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    @MapperScan("asia.creat.mapper")
    @Import({DocumentServiceImpl.class, DocumentParseServiceImpl.class, DocumentContentServiceImpl.class,
            DocumentTextExtractor.class, SpaceRoleAspect.class, ResourcePermissionHelper.class})
    static class Config {
        @Bean DataSource dataSource() {
            return new DriverManagerDataSource(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
        }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            factory.setConfiguration(configuration);
            factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:/asia/creat/mapper/*.xml"));
            factory.setPlugins(new MpConfig().mybatisPlusInterceptor());
            return factory.getObject();
        }
        @Bean PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
        @Bean ParseProperties parseProperties() { return new ParseProperties(); }
        @Bean RecentDocumentService recentDocumentService() { return mock(RecentDocumentService.class); }
        @Bean FileStorageService fileStorageService() {
            MinioProperties properties = new MinioProperties();
            properties.setBucketPrivate("parse-test-private");
            properties.setRegion("us-east-1");
            return new MinioFileStorageServiceImpl(minio(), minio(), properties);
        }
    }
}
