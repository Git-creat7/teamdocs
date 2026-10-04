package asia.creat.teamdocsbackend.service.impl;

import asia.creat.common.BucketType;
import asia.creat.common.exception.BusinessException;
import asia.creat.config.ParseProperties;
import asia.creat.entity.Document;
import asia.creat.entity.DocumentContent;
import asia.creat.entity.ParseStatus;
import asia.creat.entity.SpaceMember;
import asia.creat.entity.SpaceRole;
import asia.creat.helper.ResourcePermissionHelper;
import asia.creat.mapper.DocumentMapper;
import asia.creat.parse.DocumentTextExtractor;
import asia.creat.parse.ExtractedText;
import asia.creat.security.LoginUser;
import asia.creat.security.SpaceContext;
import asia.creat.service.DocumentContentService;
import asia.creat.service.FileStorageService;
import asia.creat.service.impl.DocumentParseServiceImpl;
import asia.creat.vo.DocumentParseStatusVO;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import asia.creat.service.DocumentIndexSync;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static asia.creat.parse.DocumentParseWorker.SOURCE_CACHE;

@ExtendWith(MockitoExtension.class)
class DocumentParseServiceImplTest {
    private static final LoginUser OWNER = new LoginUser(7L, "alice");

    @Mock
    private DocumentMapper documentMapper;
    @Mock
    private DocumentContentService documentContentService;
    @Mock
    private FileStorageService fileStorageService;
    @Mock
    private DocumentTextExtractor textExtractor;

    @BeforeAll
    static void initializeTableMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Document.class);
    }

    private ParseProperties properties;
    @Mock
    private DocumentIndexSync documentIndexSync;

    private DocumentParseServiceImpl service;

    @BeforeEach
    void setUp() {
        SOURCE_CACHE.invalidateAll();
        SOURCE_CACHE.cleanUp();
        properties = new ParseProperties();
        properties.setMaxBytes(10);
        properties.setChunkSize(1000);
        properties.setChunkOverlap(120);
        service = new DocumentParseServiceImpl(
                documentMapper,
                documentContentService,
                fileStorageService,
                textExtractor,
                properties,
                new ResourcePermissionHelper(),
                documentIndexSync
        );
    }

    @AfterEach
    void tearDown() {
        SpaceContext.clear();
        SOURCE_CACHE.invalidateAll();
        SOURCE_CACHE.cleanUp();
    }

    @Test
    void missedClaimDoesNotReadFile() {
        when(documentMapper.selectById(9L)).thenReturn(document(4L));
        when(documentMapper.update(isNull(), any())).thenReturn(0);

        service.parseDocument(9L);

        verify(fileStorageService, never()).open(any(), any());
        verify(documentContentService, never()).publishIfParsing(any(), any(), any(), any());
    }

    @Test
    void oversizedFileIsSkippedWithoutOpening() {
        when(documentMapper.update(isNull(), any())).thenReturn(1);
        when(documentMapper.selectById(9L)).thenReturn(document(11L));

        service.parseDocument(9L);

        verify(fileStorageService, never()).open(any(), any());
        verify(documentContentService).discardIfParsing(9L, 1L, 2, ParseStatus.SKIPPED, "文件超过解析大小上限");
    }

    @Test
    void extractorReusesTheSameBytesHeldByTheBoundedCache() throws IOException {
        Document document = document(4L);
        document.setName("notes.txt");
        document.setFileType("text/plain");
        byte[] original = new byte[]{1, 2, 3, 4};
        ByteArrayInputStream input = new ByteArrayInputStream(original);
        when(documentMapper.selectById(9L)).thenReturn(document);
        when(documentMapper.update(isNull(), any())).thenReturn(1);
        when(fileStorageService.open(BucketType.PRIVATE, document.getFilePath())).thenReturn(input);
        when(textExtractor.extractSource(eq("notes.txt"), eq("text/plain"), any()))
                .thenReturn(ExtractedText.skipped("没有可提取文本"));

        service.parseDocument(9L);

        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(textExtractor).extractSource(eq("notes.txt"), eq("text/plain"), bytes.capture());
        assertArrayEquals(original, bytes.getValue());
        assertSame(SOURCE_CACHE.getIfPresent("9:2"), bytes.getValue());
        assertEquals(0, input.available());
        verify(textExtractor, never()).extract(any(), any(), any());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = 4)
    void actualStreamLimitAppliesWhenFileSizeIsMissingOrInaccurate(Long declaredSize) throws IOException {
        properties.setMaxBytes(32);
        Document document = document(declaredSize);
        document.setName("notes.txt");
        document.setFileType("text/plain");
        AtomicBoolean closed = new AtomicBoolean();
        ByteArrayInputStream input = new ByteArrayInputStream(new byte[4096]) {
            @Override
            public void close() {
                closed.set(true);
            }
        };
        when(documentMapper.selectById(9L)).thenReturn(document);
        when(documentMapper.update(isNull(), any())).thenReturn(1);
        when(fileStorageService.open(BucketType.PRIVATE, document.getFilePath())).thenReturn(input);
        service = new DocumentParseServiceImpl(documentMapper, documentContentService, fileStorageService,
                new DocumentTextExtractor(properties), properties, new ResourcePermissionHelper(), documentIndexSync);

        service.parseDocument(9L);

        assertEquals(4096 - 33, input.available(), "Stop after maxBytes plus one overflow-detection byte");
        assertTrue(closed.get());
        assertNull(SOURCE_CACHE.getIfPresent("9:2"), "Oversized sources must not enter the cache");
        verify(documentContentService).discardIfParsing(
                9L, 1L, 2, ParseStatus.FAILED, "文件读取或解析失败，请检查文件后重试");
        verify(documentContentService, never()).publishIfParsing(any(), any(), any(), any());
        verify(documentIndexSync, never()).afterCommit(any());
    }

    @Test
    void extractFailureIsRecordedAsFailed() throws IOException {
        Document document = document(4L);
        document.setName("notes.txt");
        document.setFileType("text/plain");
        when(documentMapper.update(isNull(), any())).thenReturn(1);
        when(documentMapper.selectById(9L)).thenReturn(document);
        when(fileStorageService.open(BucketType.PRIVATE, "space/1/notes.txt"))
                .thenReturn(new ByteArrayInputStream(new byte[0]));
        when(textExtractor.extractSource(eq("notes.txt"), eq("text/plain"), any()))
                .thenThrow(new IOException("不是有效的 UTF-8 文本"));

        service.parseDocument(9L);

        verify(documentContentService).discardIfParsing(
                9L, 1L, 2, ParseStatus.FAILED, "文件读取或解析失败，请检查文件后重试");
        verify(documentContentService, never()).publishIfParsing(any(), any(), any(), any());
    }

    @Test
    void readyChunksUseGlobalIndexAndPage() throws IOException {
        Document document = document(4L);
        document.setName("report.pdf");
        document.setFileType("application/pdf");
        when(documentMapper.update(isNull(), any())).thenReturn(1);
        when(documentMapper.selectById(9L)).thenReturn(document);
        when(fileStorageService.open(any(), any())).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(textExtractor.extractSource(any(), any(), any())).thenReturn(ExtractedText.of(List.of(
                new ExtractedText.Segment(1, "第一页"),
                new ExtractedText.Segment(2, "第二页")
        )));

        service.parseDocument(9L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DocumentContent>> chunks = ArgumentCaptor.forClass(List.class);
        verify(documentContentService).publishIfParsing(eq(9L), eq(1L), eq(2), chunks.capture());
        assertEquals(0, chunks.getValue().get(0).getChunkIndex());
        assertEquals(1, chunks.getValue().get(0).getPageNumber());
        assertEquals(1, chunks.getValue().get(1).getChunkIndex());
        assertEquals(2, chunks.getValue().get(1).getPageNumber());
    }

    @Test
    void expiredPublishIsNotOverwritten() throws IOException {
        Document document = document(4L);
        document.setName("notes.txt");
        when(documentMapper.update(isNull(), any())).thenReturn(1);
        when(documentMapper.selectById(9L)).thenReturn(document);
        when(fileStorageService.open(any(), any())).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(textExtractor.extractSource(any(), any(), any()))
                .thenReturn(ExtractedText.of(List.of(new ExtractedText.Segment(null, "正文"))));
        when(documentContentService.publishIfParsing(any(), any(), any(), any())).thenReturn(false);

        service.parseDocument(9L);

        verify(documentContentService, never()).discardIfParsing(any(), any(), any(), any(), any());
    }

    @Test
    void memberCannotReparseSomeoneElsesDocument() {
        Document document = document(4L);
        document.setUploadBy(8L);
        document.setParseStatus(ParseStatus.FAILED);
        when(documentMapper.selectById(9L)).thenReturn(document);
        SpaceMember member = new SpaceMember();
        member.setRole(SpaceRole.MEMBER);
        SpaceContext.set(member);

        assertThrows(BusinessException.class, () -> service.reparse(1L, 9L, OWNER));
        verify(documentMapper, never()).update(isNull(), any());
    }

    @Test
    void failedDocumentCanBeQueuedAgain() {
        Document document = document(4L);
        document.setParseStatus(ParseStatus.FAILED);
        document.setChunkCount(3);
        when(documentMapper.selectById(9L)).thenReturn(document);
        when(documentMapper.update(isNull(), any())).thenReturn(1);
        SpaceMember member = new SpaceMember();
        member.setRole(SpaceRole.OWNER);
        SpaceContext.set(member);

        DocumentParseStatusVO status = service.reparse(1L, 9L, OWNER);

        assertEquals(ParseStatus.PENDING, status.getParseStatus());
        assertEquals(0, status.getChunkCount());
        assertEquals(3, status.getParseVersion());
    }

    @Test
    void parsingDocumentCannotBeReparsed() {
        Document document = document(4L);
        document.setParseStatus(ParseStatus.PARSING);
        when(documentMapper.selectById(9L)).thenReturn(document);
        when(documentMapper.update(isNull(), any())).thenReturn(0);
        SpaceMember member = new SpaceMember();
        member.setRole(SpaceRole.OWNER);
        SpaceContext.set(member);

        BusinessException error = assertThrows(BusinessException.class, () -> service.reparse(1L, 9L, OWNER));
        assertEquals("当前状态不能重新解析", error.getMessage());
    }

    private Document document(Long fileSize) {
        Document document = new Document();
        document.setId(9L);
        document.setSpaceId(1L);
        document.setUploadBy(7L);
        document.setName("scan.png");
        document.setFileType("image/png");
        document.setFileSize(fileSize);
        document.setFilePath("space/1/notes.txt");
        document.setParseVersion(2);
        document.setParseStatus(ParseStatus.PENDING);
        return document;
    }
}
