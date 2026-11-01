package asia.creat.teamdocsbackend.service.impl;

import asia.creat.common.BucketType;
import asia.creat.common.exception.BusinessException;
import asia.creat.dto.MoveDocumentDTO;
import asia.creat.dto.PageQuery;
import asia.creat.entity.Document;
import asia.creat.entity.Folder;
import asia.creat.entity.ParseStatus;
import asia.creat.entity.SpaceMember;
import asia.creat.entity.SpaceRole;
import asia.creat.helper.ResourcePermissionHelper;
import asia.creat.mapper.DocumentMapper;
import asia.creat.mapper.FolderMapper;
import asia.creat.security.LoginUser;
import asia.creat.security.SpaceContext;
import asia.creat.service.DocumentContentService;
import asia.creat.service.DocumentIndexSync;
import asia.creat.service.FileStorageService;
import asia.creat.service.RecentDocumentService;
import asia.creat.service.impl.DocumentServiceImpl;
import asia.creat.vo.DocumentDetailVO;
import asia.creat.vo.DocumentPreviewVO;
import asia.creat.vo.BatchUploadResultVO;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentServiceImplTest {
    private static final Long SPACE_ID = 1L;
    private static final Long USER_ID = 7L;
    private static final LoginUser LOGIN_USER = new LoginUser(USER_ID, "alice");

    @Mock
    private DocumentMapper documentMapper;

    @Mock
    private FileStorageService fileStorageService;

    @Mock
    private ResourcePermissionHelper permissionHelper;

    @Mock
    private FolderMapper folderMapper;

    @Mock
    private RecentDocumentService recentDocumentService;





    @Mock
    private DocumentContentService documentContentService;


    @Mock
    private DocumentIndexSync documentIndexSync;

    @Mock
    private PlatformTransactionManager transactionManager;

    private DocumentServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new DocumentServiceImpl(
                fileStorageService,
                documentMapper,
                permissionHelper,
                folderMapper,
                recentDocumentService,
                documentContentService,
                documentIndexSync,
                transactionManager
        );

        SpaceMember member = new SpaceMember();
        member.setRole(SpaceRole.MEMBER);
        SpaceContext.set(member);
    }

    @AfterEach
    void tearDown() {
        SpaceContext.clear();
    }

    // Upload stores the binary first and then writes metadata owned by the current user.
    @Test
    void uploadShouldPersistDocumentMetadata() {
        prepareUpload(0L);

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "notes.txt",
                "text/plain",
                "hello".getBytes(StandardCharsets.UTF_8)
        );

        when(documentMapper.insert(any(Document.class))).thenAnswer(invocation -> {
            Document document = invocation.getArgument(0);
            document.setId(88L);

            return 1;
        });

        Long documentId = service.upload(SPACE_ID, 0L, file, LOGIN_USER);

        verify(fileStorageService).upload(eq(file), eq(BucketType.PRIVATE), any(String.class));

        ArgumentCaptor<Document> documentCaptor = ArgumentCaptor.forClass(Document.class);

        verify(documentMapper).insert(documentCaptor.capture());

        Document saved = documentCaptor.getValue();

        assertEquals(SPACE_ID, saved.getSpaceId());
        assertEquals(0L, saved.getFolderId());
        assertEquals("notes.txt", saved.getName());
        assertEquals(USER_ID, saved.getUploadBy());
        assertEquals(ParseStatus.PENDING, saved.getParseStatus());
        assertEquals(0, saved.getChunkCount());
        assertEquals(0, saved.getParseVersion());
        assertEquals(88L, documentId);
    }

    @Test
    void uploadShouldDeleteObjectWhenMetadataInsertFails() {
        prepareUpload(0L);

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "notes.txt",
                "text/plain",
                "hello".getBytes(StandardCharsets.UTF_8)
        );
        RuntimeException databaseFailure = new RuntimeException("database unavailable");

        when(documentMapper.insert(any(Document.class))).thenThrow(databaseFailure);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> service.upload(SPACE_ID, 0L, file, LOGIN_USER));

        ArgumentCaptor<String> objectKeyCaptor = ArgumentCaptor.forClass(String.class);

        verify(fileStorageService).upload(eq(file), eq(BucketType.PRIVATE), objectKeyCaptor.capture());
        verify(fileStorageService).delete(BucketType.PRIVATE, objectKeyCaptor.getValue());

        assertSame(databaseFailure, thrown);
    }

    @Test
    void uploadShouldTreatZeroInsertedRowsAsFailure() {
        prepareUpload(0L);

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "notes.txt",
                "text/plain",
                "hello".getBytes(StandardCharsets.UTF_8)
        );

        when(documentMapper.insert(any(Document.class))).thenReturn(0);

        assertThrows(BusinessException.class,
                () -> service.upload(SPACE_ID, 0L, file, LOGIN_USER));

        ArgumentCaptor<String> objectKeyCaptor = ArgumentCaptor.forClass(String.class);

        verify(fileStorageService).upload(eq(file), eq(BucketType.PRIVATE), objectKeyCaptor.capture());
        verify(fileStorageService).delete(BucketType.PRIVATE, objectKeyCaptor.getValue());
    }

    @Test
    void uploadShouldRejectFolderFromAnotherSpace() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "notes.txt",
                "text/plain",
                "hello".getBytes(StandardCharsets.UTF_8)
        );

        when(folderMapper.selectCount(any())).thenReturn(0L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.upload(SPACE_ID, 20L, file, LOGIN_USER));

        assertEquals("目标文件夹不存在", ex.getMessage());
        verify(fileStorageService, never()).upload(any(), any(), any());
        verify(documentMapper, never()).insert(any(Document.class));
    }

    @Test
    void uploadShouldAcceptFolderInCurrentSpace() {
        prepareUpload(20L);

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "notes.txt",
                "text/plain",
                "hello".getBytes(StandardCharsets.UTF_8)
        );

        when(folderMapper.selectCount(any())).thenReturn(1L);
        when(documentMapper.insert(any(Document.class))).thenReturn(1);

        service.upload(SPACE_ID, 20L, file, LOGIN_USER);

        ArgumentCaptor<Document> documentCaptor = ArgumentCaptor.forClass(Document.class);

        verify(documentMapper).insert(documentCaptor.capture());

        assertEquals(20L, documentCaptor.getValue().getFolderId());
        verify(fileStorageService).upload(eq(file), eq(BucketType.PRIVATE), any(String.class));
    }

    private void prepareUpload(Long folderId) {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(documentMapper.lockUploadDirectory(SPACE_ID, folderId)).thenReturn(folderId == 0 ? SPACE_ID : folderId);
    }

    static Stream<Arguments> duplicateNames() {
        return Stream.of(
                Arguments.of("需求.pdf", "需求(1).pdf"),
                Arguments.of("README", "README(1)"),
                Arguments.of(".env", ".env(1)"),
                Arguments.of("archive.tar.gz", "archive.tar(1).gz"),
                Arguments.of("a".repeat(251) + ".pdf", "a".repeat(248) + "(1).pdf"),
                Arguments.of("😀".repeat(251) + ".pdf", "😀".repeat(248) + "(1).pdf")
        );
    }

    @ParameterizedTest
    @MethodSource("duplicateNames")
    void duplicateUploadShouldNumberTheStemAndPreserveTheExtension(String original, String expected) {
        prepareUpload(0L);
        when(documentMapper.existsActiveName(SPACE_ID, 0L, original)).thenReturn(true);
        when(documentMapper.insert(any(Document.class))).thenReturn(1);

        service.upload(SPACE_ID, 0L, new MockMultipartFile("file", original, "text/plain", new byte[]{1}), LOGIN_USER);

        ArgumentCaptor<Document> captured = ArgumentCaptor.forClass(Document.class);

        verify(documentMapper).insert(captured.capture());

        assertEquals(expected, captured.getValue().getName());
    }

    @Test
    void duplicateUploadShouldSkipNumbersAlreadyInUse() {
        prepareUpload(0L);
        when(documentMapper.existsActiveName(SPACE_ID, 0L, "notes.txt")).thenReturn(true);
        when(documentMapper.existsActiveName(SPACE_ID, 0L, "notes(1).txt")).thenReturn(true);
        when(documentMapper.existsActiveName(SPACE_ID, 0L, "notes(2).txt")).thenReturn(true);
        when(documentMapper.insert(any(Document.class))).thenReturn(1);

        MockMultipartFile file = new MockMultipartFile("file", "notes.txt", "text/plain", new byte[]{1});

        service.upload(SPACE_ID, 0L, file, LOGIN_USER);

        ArgumentCaptor<Document> captured = ArgumentCaptor.forClass(Document.class);

        verify(documentMapper).insert(captured.capture());

        assertEquals("notes(3).txt", captured.getValue().getName());

        var order = inOrder(fileStorageService, transactionManager, documentMapper);

        order.verify(fileStorageService).upload(eq(file), eq(BucketType.PRIVATE), any(String.class));
        order.verify(transactionManager).getTransaction(any());
        order.verify(documentMapper).lockUploadDirectory(SPACE_ID, 0L);
        order.verify(documentMapper).existsActiveName(SPACE_ID, 0L, "notes.txt");
        order.verify(documentMapper).insert(any(Document.class));
        order.verify(transactionManager).commit(any());
    }

    @Test
    void uploadShouldDeleteObjectIfTheDirectoryDisappearsBeforeMetadataWrite() {
        when(documentMapper.lockUploadDirectory(SPACE_ID, 0L)).thenReturn(null);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        MockMultipartFile file = new MockMultipartFile("file", "notes.txt", "text/plain", new byte[]{1});

        assertThrows(BusinessException.class, () -> service.upload(SPACE_ID, 0L, file, LOGIN_USER));

        verify(documentMapper, never()).insert(any(Document.class));
        verify(transactionManager).rollback(any());

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);

        verify(fileStorageService).upload(eq(file), eq(BucketType.PRIVATE), key.capture());
        verify(fileStorageService).delete(BucketType.PRIVATE, key.getValue());
    }

    @Test
    void uploadShouldDeleteObjectWhenMetadataCommitFails() {
        prepareUpload(0L);
        when(documentMapper.insert(any(Document.class))).thenReturn(1);

        RuntimeException failure = new RuntimeException("commit failed");

        doThrow(failure).when(transactionManager).commit(any());

        MockMultipartFile file = new MockMultipartFile("file", "notes.txt", "text/plain", new byte[]{1});

        assertSame(failure, assertThrows(RuntimeException.class,
                () -> service.upload(SPACE_ID, 0L, file, LOGIN_USER)));

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);

        verify(fileStorageService).upload(eq(file), eq(BucketType.PRIVATE), key.capture());
        verify(fileStorageService).delete(BucketType.PRIVATE, key.getValue());
    }

    @Test
    void moveShouldRejectFolderFromAnotherSpace() {
        Document document = document(10L, SPACE_ID, USER_ID, 0L);
        Folder targetFolder = new Folder();
        targetFolder.setId(20L);
        targetFolder.setSpaceId(2L);

        MoveDocumentDTO dto = new MoveDocumentDTO();
        dto.setTargetFolderId(20L);
        when(documentMapper.selectById(10L)).thenReturn(document);
        when(folderMapper.selectCount(any())).thenReturn(0L);

        assertThrows(BusinessException.class,
                () -> service.moveDocument(SPACE_ID, 10L, dto, LOGIN_USER));

        verify(documentMapper, never()).updateById(any(Document.class));
    }

    @Test
    void listByFolderShouldRejectMissingFolder() {
        when(folderMapper.selectCount(any())).thenReturn(0L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.listByFolder(SPACE_ID, 20L, new PageQuery(), LOGIN_USER));

        assertEquals("目标文件夹不存在", ex.getMessage());
        verify(documentMapper, never()).selectPage(any(), any());
    }

    @Test
    void restoreShouldRejectDocumentFromAnotherSpace() {
        when(documentMapper.selectDeletedDocument(10L))
                .thenReturn(document(10L, 2L, USER_ID, 0L));

        assertThrows(BusinessException.class,
                () -> service.restoreDocument(SPACE_ID, 10L, null, LOGIN_USER));

        verify(documentMapper, never()).updateDeleted(any(), any());
    }

    @Test
    void restoreWithoutTargetShouldFallBackToRootWhenOriginalFolderWasDeleted() {
        when(documentMapper.selectDeletedDocument(10L))
                .thenReturn(document(10L, SPACE_ID, USER_ID, 20L));
        when(folderMapper.selectById(20L)).thenReturn(null);

        service.restoreDocument(SPACE_ID, 10L, null, LOGIN_USER);

        verify(documentMapper).updateDeleted(10L, 0L);
    }

    @Test
    void restoreShouldUseRootWhenTargetFolderIsZero() {
        when(documentMapper.selectDeletedDocument(10L))
                .thenReturn(document(10L, SPACE_ID, USER_ID, 20L));

        service.restoreDocument(SPACE_ID, 10L, 0L, LOGIN_USER);

        verify(folderMapper, never()).selectById(any());
        verify(documentMapper).updateDeleted(10L, 0L);
    }

    @Test
    void downloadShouldRecordRecentOnlyAfterUrlWasGenerated() {
        Document document = document(10L, SPACE_ID, USER_ID, 0L);
        document.setName("notes.txt");
        document.setFilePath("space/1/notes.txt");
        when(documentMapper.selectById(10L)).thenReturn(document);
        when(fileStorageService.getAccessUrl(
                eq(BucketType.PRIVATE),
                eq("space/1/notes.txt"),
                any(Map.class)
        )).thenReturn("https://files.example/notes.txt");

        String url = service.downloadDocument(SPACE_ID, 10L, LOGIN_USER);

        assertEquals("https://files.example/notes.txt", url);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> paramsCaptor = ArgumentCaptor.forClass(Map.class);

        verify(fileStorageService).getAccessUrl(
                eq(BucketType.PRIVATE),
                eq("space/1/notes.txt"),
                paramsCaptor.capture()
        );

        assertEquals(
                "attachment; filename*=UTF-8''notes.txt",
                paramsCaptor.getValue().get("response-content-disposition")
        );
        verify(recentDocumentService).recordRecentDocument(USER_ID, 10L);
    }

    @Test
    void downloadShouldNotRecordRecentWhenUrlGenerationFails() {
        Document document = document(10L, SPACE_ID, USER_ID, 0L);
        document.setName("notes.txt");
        document.setFilePath("space/1/notes.txt");
        when(documentMapper.selectById(10L)).thenReturn(document);
        when(fileStorageService.getAccessUrl(any(), any(), any()))
                .thenThrow(new BusinessException("生成访问URL失败"));

        assertThrows(BusinessException.class,
                () -> service.downloadDocument(SPACE_ID, 10L, LOGIN_USER));

        verify(recentDocumentService, never()).recordRecentDocument(any(), any());
    }

    @Test
    void previewShouldReturnMetadataAndEncodedInlineUrl() {
        Document document = document(10L, SPACE_ID, USER_ID, 0L);
        document.setName("季度 报告.pdf");
        document.setFileType("application/pdf");
        document.setFileSize(1024L);
        document.setFilePath("space/1/report.pdf");
        when(documentMapper.selectById(10L)).thenReturn(document);
        when(fileStorageService.getAccessUrl(
                eq(BucketType.PRIVATE),
                eq("space/1/report.pdf"),
                any(Map.class)
        )).thenReturn("https://files.example/report.pdf");

        DocumentPreviewVO result = service.previewDocument(SPACE_ID, 10L, LOGIN_USER);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        var ordered = inOrder(fileStorageService, recentDocumentService);

        ordered.verify(fileStorageService).getAccessUrl(
                eq(BucketType.PRIVATE),
                eq("space/1/report.pdf"),
                paramsCaptor.capture()
        );
        ordered.verify(recentDocumentService).recordRecentDocument(USER_ID, 10L);

        assertEquals(1, paramsCaptor.getValue().size());
        assertEquals(
                "inline; filename*=UTF-8''%E5%AD%A3%E5%BA%A6%20%E6%8A%A5%E5%91%8A.pdf",
                paramsCaptor.getValue().get("response-content-disposition")
        );
        assertEquals(10L, result.getDocumentId());
        assertEquals("季度 报告.pdf", result.getName());
        assertEquals("application/pdf", result.getFileType());
        assertEquals(1024L, result.getFileSize());
        assertEquals("https://files.example/report.pdf", result.getUrl());
    }

    @Test
    void previewShouldNotRecordRecentWhenUrlGenerationFails() {
        Document document = document(10L, SPACE_ID, USER_ID, 0L);
        document.setName("notes.txt");
        document.setFilePath("space/1/notes.txt");
        when(documentMapper.selectById(10L)).thenReturn(document);
        when(fileStorageService.getAccessUrl(any(), any(), any()))
                .thenThrow(new BusinessException("生成访问URL失败"));

        assertThrows(
                BusinessException.class,
                () -> service.previewDocument(SPACE_ID, 10L, LOGIN_USER)
        );

        verify(recentDocumentService, never()).recordRecentDocument(any(), any());
    }

    @Test
    void purgeShouldDeleteObjectAndRelationsBeforeMetadata() {
        Document document = document(10L, SPACE_ID, USER_ID, 0L);
        document.setFilePath("space/1/notes.txt");
        document.setDeleted(1);
        when(documentMapper.selectDeletedDocument(10L)).thenReturn(document);
        when(documentMapper.lockById(10L)).thenReturn(document);
        when(documentMapper.purgeDeleteById(10L)).thenReturn(true);

        service.purgeDocument(SPACE_ID, 10L, LOGIN_USER);

        var inOrder = inOrder(fileStorageService, documentMapper, documentContentService);

        inOrder.verify(fileStorageService).delete(BucketType.PRIVATE, "space/1/notes.txt");
        inOrder.verify(documentMapper).lockById(10L);
        inOrder.verify(documentContentService).purgeByDocumentId(10L);
        inOrder.verify(documentMapper).purgeDeleteById(10L);
    }

    @Test
    void purgeShouldKeepMetadataWhenObjectDeletionFails() {
        Document document = document(10L, SPACE_ID, USER_ID, 0L);
        document.setFilePath("space/1/notes.txt");
        when(documentMapper.selectDeletedDocument(10L)).thenReturn(document);
        doThrow(new BusinessException("文件删除失败"))
                .when(fileStorageService)
                .delete(BucketType.PRIVATE, "space/1/notes.txt");

        assertThrows(BusinessException.class,
                () -> service.purgeDocument(SPACE_ID, 10L, LOGIN_USER));

        verify(documentMapper, never()).purgeDeleteById(any());
    }

    @Test
    void getDocumentDetailShouldIncludeParseStatusAndChunkCount() {
        Document document = document(10L, SPACE_ID, USER_ID, 0L);
        document.setName("spec.pdf");
        document.setParseStatus(ParseStatus.READY);
        document.setChunkCount(15);

        LocalDateTime now = LocalDateTime.now();

        document.setParsedAt(now);
        when(documentMapper.selectById(10L)).thenReturn(document);

        DocumentDetailVO vo = service.getDocumentDetail(SPACE_ID, 10L, LOGIN_USER);

        assertEquals(ParseStatus.READY, vo.getParseStatus());
        assertEquals(15, vo.getChunkCount());
        assertEquals(now, vo.getParsedAt());
        verify(recentDocumentService).recordRecentDocument(USER_ID, 10L);
    }

    private Document document(Long id, Long spaceId, Long uploadBy, Long folderId) {
        Document document = new Document();
        document.setId(id);
        document.setSpaceId(spaceId);
        document.setUploadBy(uploadBy);
        document.setFolderId(folderId);

        return document;
    }

    @Test
    void batchUploadShouldThrowWhenFilesEmptyOrNull() {
        assertThrows(BusinessException.class,
                () -> service.batchUpload(SPACE_ID, 0L, null, LOGIN_USER));
        assertThrows(BusinessException.class,
                () -> service.batchUpload(SPACE_ID, 0L, List.of(), LOGIN_USER));
    }

    @Test
    void batchUploadShouldThrowWhenCountExceedsTen() {
        List<MultipartFile> files = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            files.add(new MockMultipartFile("file", "test" + i + ".txt", "text/plain", "data".getBytes(StandardCharsets.UTF_8)));
        }
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.batchUpload(SPACE_ID, 0L, files, LOGIN_USER));
        assertTrue(ex.getMessage().contains("单次批量上传最多不能超过 10 个文件"));
    }

    @Test
    void batchUploadShouldThrowWhenTotalSizeExceeds200MB() {
        MockMultipartFile bigFile1 = new MockMultipartFile("file", "big1.bin", "application/octet-stream", new byte[0]) {
            @Override
            public long getSize() {
                return 150L * 1024 * 1024;
            }
        };
        MockMultipartFile bigFile2 = new MockMultipartFile("file", "big2.bin", "application/octet-stream", new byte[0]) {
            @Override
            public long getSize() {
                return 60L * 1024 * 1024;
            }
        };
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.batchUpload(SPACE_ID, 0L, List.of(bigFile1, bigFile2), LOGIN_USER));
        assertTrue(ex.getMessage().contains("200MB"));
    }

    @Test
    void batchUploadShouldHandlePartialFailuresForEmptyAndOversizedFiles() {
        prepareUpload(0L);

        MockMultipartFile validFile = new MockMultipartFile("file", "valid.txt", "text/plain", "valid content".getBytes(StandardCharsets.UTF_8));
        MockMultipartFile emptyFile = new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]);
        MockMultipartFile oversizedFile = new MockMultipartFile("file", "oversized.bin", "application/octet-stream", new byte[]{1}) {
            @Override
            public boolean isEmpty() {
                return false;
            }
            @Override
            public long getSize() {
                return 101L * 1024 * 1024;
            }
        };

        when(documentMapper.insert(any(Document.class))).thenAnswer(invocation -> {
            Document doc = invocation.getArgument(0);
            doc.setId(101L);
            return 1;
        });

        BatchUploadResultVO result = service.batchUpload(SPACE_ID, 0L, List.of(validFile, emptyFile, oversizedFile), LOGIN_USER);

        assertEquals(3, result.getTotalCount());
        assertEquals(1, result.getSuccessCount());
        assertEquals(2, result.getFailCount());
        assertEquals(3, result.getItems().size());

        // Check valid file
        BatchUploadResultVO.Item item0 = result.getItems().get(0);
        assertEquals("valid.txt", item0.getFileName());
        assertTrue(item0.isSuccess());
        assertEquals(101L, item0.getDocumentId());

        // Check empty file
        BatchUploadResultVO.Item item1 = result.getItems().get(1);
        assertEquals("empty.txt", item1.getFileName());
        assertFalse(item1.isSuccess());
        assertEquals("文件内容不能为空", item1.getErrorMessage());

        // Check oversized file
        BatchUploadResultVO.Item item2 = result.getItems().get(2);
        assertEquals("oversized.bin", item2.getFileName());
        assertFalse(item2.isSuccess());
        assertEquals("单文件大小不能超过 100MB", item2.getErrorMessage());
    }

    @Test
    void batchUploadShouldSucceedForAllValidFiles() {
        prepareUpload(0L);

        MockMultipartFile file1 = new MockMultipartFile("file", "f1.txt", "text/plain", "content 1".getBytes(StandardCharsets.UTF_8));
        MockMultipartFile file2 = new MockMultipartFile("file", "f2.txt", "text/plain", "content 2".getBytes(StandardCharsets.UTF_8));

        when(documentMapper.insert(any(Document.class))).thenAnswer(invocation -> {
            Document doc = invocation.getArgument(0);
            doc.setId(201L);
            return 1;
        });

        BatchUploadResultVO result = service.batchUpload(SPACE_ID, 0L, List.of(file1, file2), LOGIN_USER);

        assertEquals(2, result.getTotalCount());
        assertEquals(2, result.getSuccessCount());
        assertEquals(0, result.getFailCount());
        assertEquals(2, result.getItems().size());
        assertTrue(result.getItems().get(0).isSuccess());
        assertTrue(result.getItems().get(1).isSuccess());
    }
}
