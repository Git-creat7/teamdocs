package asia.creat.teamdocsbackend.service.impl;

import asia.creat.common.BucketType;
import asia.creat.common.exception.BusinessException;
import asia.creat.dto.MoveDocumentDTO;
import asia.creat.entity.Document;
import asia.creat.entity.Folder;
import asia.creat.entity.SpaceMember;
import asia.creat.entity.SpaceRole;
import asia.creat.helper.ResourcePermissionHelper;
import asia.creat.mapper.DocumentMapper;
import asia.creat.mapper.FolderMapper;
import asia.creat.security.LoginUser;
import asia.creat.security.SpaceContext;
import asia.creat.service.FileStorageService;
import asia.creat.service.RecentDocumentService;
import asia.creat.service.impl.DocumentServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Map;

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

    private DocumentServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new DocumentServiceImpl(
                documentMapper,
                fileStorageService,
                permissionHelper,
                folderMapper,
                recentDocumentService
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
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "notes.txt",
                "text/plain",
                "hello".getBytes(StandardCharsets.UTF_8)
        );
        when(documentMapper.insert(any(Document.class))).thenReturn(1);

        service.upload(SPACE_ID, 0L, file, LOGIN_USER);

        verify(fileStorageService).upload(eq(file), eq(BucketType.PRIVATE), any(String.class));
        ArgumentCaptor<Document> documentCaptor = ArgumentCaptor.forClass(Document.class);
        verify(documentMapper).insert(documentCaptor.capture());
        Document saved = documentCaptor.getValue();
        assertEquals(SPACE_ID, saved.getSpaceId());
        assertEquals(0L, saved.getFolderId());
        assertEquals("notes.txt", saved.getName());
        assertEquals(USER_ID, saved.getUploadBy());
    }

    @Test
    void uploadShouldDeleteObjectWhenMetadataInsertFails() {
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
        verify(recentDocumentService).recordRecentDocument(USER_ID, 10L);
    }

    @Test
    void downloadShouldNotRecordRecentWhenUrlGenerationFails() {
        Document document = document(10L, SPACE_ID, USER_ID, 0L);
        document.setFilePath("space/1/notes.txt");
        when(documentMapper.selectById(10L)).thenReturn(document);
        when(fileStorageService.getAccessUrl(any(), any(), any()))
                .thenThrow(new BusinessException("生成访问URL失败"));

        assertThrows(BusinessException.class,
                () -> service.downloadDocument(SPACE_ID, 10L, LOGIN_USER));

        verify(recentDocumentService, never()).recordRecentDocument(any(), any());
    }

    @Test
    void purgeShouldDeleteObjectBeforeMetadata() {
        Document document = document(10L, SPACE_ID, USER_ID, 0L);
        document.setFilePath("space/1/notes.txt");
        when(documentMapper.selectDeletedDocument(10L)).thenReturn(document);
        when(documentMapper.purgeDeleteById(10L)).thenReturn(true);

        service.purgeDocument(SPACE_ID, 10L, LOGIN_USER);

        var inOrder = inOrder(fileStorageService, documentMapper);
        inOrder.verify(fileStorageService).delete(BucketType.PRIVATE, "space/1/notes.txt");
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

    private Document document(Long id, Long spaceId, Long uploadBy, Long folderId) {
        Document document = new Document();
        document.setId(id);
        document.setSpaceId(spaceId);
        document.setUploadBy(uploadBy);
        document.setFolderId(folderId);
        return document;
    }
}
