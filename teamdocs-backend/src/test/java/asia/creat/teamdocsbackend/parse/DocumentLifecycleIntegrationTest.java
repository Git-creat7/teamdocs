package asia.creat.teamdocsbackend.parse;

import asia.creat.aspect.SpaceRoleAspect;
import asia.creat.config.ElasticsearchProperties;
import asia.creat.config.RetrievalProperties;
import asia.creat.mapper.DocumentContentMapper;
import asia.creat.service.ChunkIndex;
import asia.creat.service.DocumentChunkQueryService;
import asia.creat.service.DocumentIndexSync;
import asia.creat.service.impl.ElasticsearchChunkIndex;
import asia.creat.service.impl.DocumentChunkQueryServiceImpl;
import asia.creat.vo.ChunkHitVO;
import org.springframework.test.context.TestPropertySource;
import org.springframework.core.io.ClassPathResource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringJUnitConfig(DocumentLifecycleIntegrationTest.Config.class)
@TestPropertySource(properties = "teamdocs.elasticsearch.enabled=true")
class DocumentLifecycleIntegrationTest {
    private static final LoginUser OWNER = new LoginUser(7L, "owner");

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withCommand("--ngram-token-size=2", "--innodb-ft-enable-stopword=OFF");
    @Container
    static final GenericContainer<?> ES = new GenericContainer<>(DockerImageName.parse(System.getProperty(
            "teamdocs.test.elasticsearch-image", "teamdocs-elasticsearch:8.15.3")))
            .withEnv("discovery.type", "single-node")
            .withEnv("xpack.security.enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withCreateContainerCmdModifier(command -> command.getHostConfig().withMemory(1536L * 1024 * 1024))
            .withExposedPorts(9200)
            .waitingFor(Wait.forHttp("/").forPort(9200).withStartupTimeout(Duration.ofMinutes(3)));
    @Container
    static final GenericContainer<?> MINIO = new GenericContainer<>(DockerImageName.parse(System.getProperty(
            "teamdocs.test.minio-image", "ghcr.io/git-creat7/teamdocs/minio@sha256:648817f3b321ec7a2f86c594ba468fa19eff8ee3ac17a07c03acf7a8a35fda33")))
            .withEnv("MINIO_ROOT_USER", "test-access-key")
            .withEnv("MINIO_ROOT_PASSWORD", "test-secret-key")
            .withCommand("server", "/data")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));

    @Autowired ChunkIndex index;
    @Autowired DocumentChunkQueryService retrieval;
    @Autowired DocumentContentMapper chunks;
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
        jdbc.update("DELETE FROM folder");
        jdbc.update("DELETE FROM space_member");
        jdbc.update("DELETE FROM space");
        jdbc.update("INSERT INTO space (id,name,owner_id) VALUES (1,'解析测试',7)");
        jdbc.update("INSERT INTO space_member (space_id,user_id,role) VALUES (1,7,'OWNER'),(1,8,'MEMBER')");
        index.rebuild();
    }

    @Test
    void duplicateUploadsAreNumberedWithoutReplacingExistingObjects() throws Exception {
        List<String> names = List.of("需求.pdf", "需求(1).pdf", "需求(2).pdf");
        Set<String> objectKeys = new HashSet<>();
        for (int i = 0; i < names.size(); i++) {
            Long id = upload("需求.pdf", "body-" + i);
            Document document = documentMapper.selectById(id);
            assertEquals(names.get(i), document.getName());
            assertTrue(objectKeys.add(document.getFilePath()));
            try (InputStream stream = storage.open(asia.creat.common.BucketType.PRIVATE, document.getFilePath())) {
                assertEquals("body-" + i, new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM document WHERE deleted=0", Integer.class));
    }

    @Test
    void uploadNamesAreScopedToActiveFilesInTheSameSpaceAndFolder() {
        Long original = upload("Report.txt", "original");
        assertEquals("report(1).txt", documentMapper.selectById(upload("report.txt", "case duplicate")).getName());
        jdbc.update("INSERT INTO folder (id,space_id,parent_id,name,created_by) VALUES (50,1,0,'nested',7)");
        Long nested = documents.upload(1L, 50L,
                new MockMultipartFile("file", "Report.txt", "text/plain", new byte[]{1}), OWNER);
        assertEquals("Report.txt", documentMapper.selectById(nested).getName());

        jdbc.update("INSERT INTO space (id,name,owner_id) VALUES (2,'other',7)");
        jdbc.update("INSERT INTO space_member (space_id,user_id,role) VALUES (2,7,'OWNER')");
        Long otherSpace = documents.upload(2L, 0L,
                new MockMultipartFile("file", "Report.txt", "text/plain", new byte[]{2}), OWNER);
        assertEquals("Report.txt", documentMapper.selectById(otherSpace).getName());

        jdbc.update("UPDATE document SET deleted=1 WHERE id=?", original);
        assertEquals("Report.txt", documentMapper.selectById(upload("Report.txt", "replacement name only")).getName());
        assertEquals(1, jdbc.queryForObject("SELECT deleted FROM document WHERE id=?", Integer.class, original));
    }

    @Test
    void concurrentUploadsChooseDistinctNamesInRootAndNestedFolders() throws Exception {
        jdbc.update("INSERT INTO folder (id,space_id,parent_id,name,created_by) VALUES (51,1,0,'nested',7)");
        for (Long folderId : List.of(0L, 51L)) {
            ExecutorService executor = Executors.newFixedThreadPool(4);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Long>> results = new ArrayList<>();
            try {
                for (int i = 0; i < 4; i++) {
                    results.add(executor.submit(() -> {
                        assertTrue(start.await(10, TimeUnit.SECONDS));
                        return documents.upload(1L, folderId,
                                new MockMultipartFile("file", "parallel.txt", "text/plain", new byte[]{1}), OWNER);
                    }));
                }
                start.countDown();
                Set<String> names = new HashSet<>();
                Set<String> objectKeys = new HashSet<>();
                for (Future<Long> result : results) {
                    Document document = documentMapper.selectById(result.get(30, TimeUnit.SECONDS));
                    names.add(document.getName());
                    assertTrue(objectKeys.add(document.getFilePath()));
                }
                assertEquals(Set.of("parallel.txt", "parallel(1).txt", "parallel(2).txt", "parallel(3).txt"), names);
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
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
        assertFalse(index.search(1L, "备份", 6).isEmpty());
        assertEquals(modifiedAt, ready.getUpdatedAt());
        List<Long> originalIds = content.getChunksByDocumentId(id).stream().map(DocumentContent::getId).toList();
        parsing.parseDocument(id);
        assertEquals(originalIds, content.getChunksByDocumentId(id).stream().map(DocumentContent::getId).toList());

        assertThrows(BusinessException.class, () -> parsing.reparse(1L, id, OWNER));
        jdbc.update("UPDATE document SET parse_started_at=DATE_SUB(NOW(), INTERVAL 1 MINUTE),updated_at=updated_at WHERE id=?", id);
        var pending = parsing.reparse(1L, id, OWNER);
        assertEquals(ready.getParseVersion() + 1, pending.getParseVersion());
        assertTrue(index.search(1L, "备份", 6).isEmpty());
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
        assertFalse(index.search(1L, "删除", 6).isEmpty());
        documents.deleteDocument(1L, id, OWNER);
        assertTrue(index.search(1L, "删除", 6).isEmpty());
        assertTrue(content.getChunksByDocumentId(id).isEmpty());
        assertEquals(1, chunkCount(id));
        documents.restoreDocument(1L, id, 0L, OWNER);
        assertEquals(1, content.getChunksByDocumentId(id).size());
        assertFalse(index.search(1L, "删除", 6).isEmpty());
        documents.deleteDocument(1L, id, OWNER);
        documents.purgeDocument(1L, id, OWNER);
        assertEquals(0, chunkCount(id));
        assertTrue(index.search(1L, "删除", 6).isEmpty());
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

    @Test
    void sameSamplesCompareMysqlAndElasticRecallWithEscapedHighlight() throws Exception {
        loadRetrievalSamples();
        assertEquals(5, index.rebuild());
        assertEquals(5, index.rebuild());
        for (String keyword : List.of("上线检查", "回滚 备份", "api", "zip")) {
            var mysql = chunks.searchChunks(1L, DocumentChunkQueryServiceImpl.matchQuery(keyword), 6);
            var elastic = index.search(1L, keyword, 6);
            System.out.println("RECALL " + keyword + " mysql=" + keys(mysql)
                    + " elastic=" + elastic.stream().map(hit -> hit.getDocumentId() + "#" + chunks.findReadableChunk(1L, hit.getDocumentId(), hit.getChunkId(), hit.getParseVersion()).getChunkIndex()).toList());
            assertFalse(mysql.isEmpty());
            assertFalse(elastic.isEmpty());
        }
        assertTrue(index.search(1L, "上线检查", 6).stream().anyMatch(hit -> hit.getDocumentId() == 10L));
        assertEquals(List.of(11L), index.search(2L, "上线检查", 6).stream().map(hit -> hit.getDocumentId()).toList());
        assertTrue(index.search(3L, "上线检查", 6).isEmpty());
        assertTrue(retrieval.searchChunks(1L, "上线检查", OWNER).stream().anyMatch(hit -> hit.getHighlight() != null));

        Long id = upload("html.txt", "上线检查 <script>alert(1)</script>：先备份数据库。");
        parsing.parseDocument(id);
        var hit = index.search(1L, "备份", 6).stream().filter(row -> row.getDocumentId().equals(id)).findFirst().orElseThrow();
        assertTrue(hit.getHighlight().contains("<mark>"));
        assertFalse(hit.getHighlight().contains("<script>"));
        assertTrue(hit.getHighlight().contains("&lt;script&gt;"));
        var verified = retrieval.searchChunks(1L, "备份", OWNER).stream()
                .filter(row -> row.getDocumentId().equals(id)).findFirst().orElseThrow();
        assertTrue(verified.getExcerpt().contains("<script>"));
        assertNotNull(verified.getHighlight());
    }

    @Test
    void staleForeignDeletedAndNonReadyCandidatesAreRecheckedAgainstMysql() throws Exception {
        loadRetrievalSamples();
        index.rebuild();
        // 模拟索引空间信息过期，不能凭 ES 的 space_id 授权。
        esRequest("POST", "/test-chunks/_update/11_0?refresh=true", "{\"doc\":{\"space_id\":1}}");
        jdbc.update("UPDATE document SET parse_version=4 WHERE id=10");
        var current = retrieval.searchChunks(1L, "上线检查", OWNER);
        assertTrue(current.stream().noneMatch(hit -> hit.getDocumentId() == 11L));
        assertTrue(current.stream().anyMatch(hit -> hit.getDocumentId() == 10L && hit.getParseVersion() == 4));
        for (String state : List.of("deleted=1", "deleted=0,parse_status='PENDING'")) {
            jdbc.update("UPDATE document SET " + state + " WHERE id=10");
            assertTrue(retrieval.searchChunks(1L, "上线检查", OWNER).stream().noneMatch(hit -> hit.getDocumentId() == 10L));
        }
        jdbc.update("DELETE FROM space_member WHERE space_id=1 AND user_id=7");
        assertThrows(BusinessException.class, () -> retrieval.searchChunks(1L, "上线检查", OWNER));
    }

    @Test
    void missingIndexEntriesUseMysqlAndRebuildRemovesStaleRows() throws Exception {
        loadRetrievalSamples();
        index.rebuild();
        esRequest("DELETE", "/test-chunks/_doc/10_0?refresh=true", null);
        assertTrue(retrieval.searchChunks(1L, "上线检查", OWNER).stream().anyMatch(hit -> hit.getDocumentId() == 10L));
        jdbc.update("UPDATE document SET deleted=1 WHERE id=11");
        assertEquals(4, index.rebuild());
        assertEquals(4, index.rebuild());
        assertTrue(index.search(2L, "上线检查", 6).isEmpty());
        assertTrue(esRequest("GET", "/test-chunks/_count", null).contains("\"count\":4"));
    }

    // 真实停服会破坏本组共享依赖；放在最后，由 Testcontainers 统一清理，避免污染其他用例。
    @Test
    @Order(Integer.MAX_VALUE)
    void stoppedElasticFallsBackWithoutBreakingUploadParseAndPreview() throws Exception {
        loadRetrievalSamples();
        index.rebuild();
        ES.getDockerClient().stopContainerCmd(ES.getContainerId()).withTimeout(1).exec();
        assertTrue(retrieval.searchChunks(1L, "上线检查", OWNER).stream().anyMatch(hit -> hit.getDocumentId() == 10L));
        Long id = upload("offline.txt", "离线期间依旧可以解析和下载");
        parsing.parseDocument(id);
        assertEquals(ParseStatus.READY, documentMapper.selectById(id).getParseStatus());
        assertNotNull(documents.downloadDocument(1L, id, OWNER));
        assertNotNull(documents.previewDocument(1L, id, OWNER));
    }

    private void loadRetrievalSamples() throws Exception {
        jdbc.update("DELETE FROM space_member");
        jdbc.update("DELETE FROM space");
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new EncodedResource(new ClassPathResource("retrieval-samples.sql"), StandardCharsets.UTF_8));
        }
        jdbc.update("INSERT INTO space_member (space_id,user_id,role) VALUES (1,7,'OWNER'),(2,7,'OWNER')");
    }

    private static List<String> keys(List<ChunkHitVO> hits) {
        return hits.stream().map(hit -> hit.getDocumentId() + "#" + hit.getChunkIndex()).toList();
    }

    private static String esUrl() { return "http://" + ES.getHost() + ":" + ES.getMappedPort(9200); }

    private static String esRequest(String method, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(esUrl() + path)).header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10)).method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() < 300, response.body());
        return response.body();
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
            DocumentTextExtractor.class, SpaceRoleAspect.class, ResourcePermissionHelper.class,
            DocumentIndexSync.class, ElasticsearchChunkIndex.class, DocumentChunkQueryServiceImpl.class})
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
        @Bean ElasticsearchProperties elasticsearchProperties() {
            ElasticsearchProperties properties = new ElasticsearchProperties();
            properties.setEnabled(true);
            properties.setUrl(esUrl());
            properties.setIndex("test-chunks");
            return properties;
        }
        @Bean RetrievalProperties retrievalProperties() { return new RetrievalProperties(); }
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
