package asia.creat.teamdocsbackend.service.impl;

import asia.creat.common.BucketType;
import asia.creat.common.exception.BusinessException;
import asia.creat.entity.Document;
import asia.creat.mapper.DocumentContentMapper;
import asia.creat.mapper.DocumentMapper;
import asia.creat.mapper.SpaceMemberMapper;
import asia.creat.parse.DocumentImageReader;
import asia.creat.security.LoginUser;
import asia.creat.service.DocumentImageService;
import asia.creat.service.FileStorageService;
import asia.creat.vo.ChunkHitVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocumentImageServiceTest {
    private final DocumentContentMapper chunks = mock(DocumentContentMapper.class);
    private final DocumentMapper documents = mock(DocumentMapper.class);
    private final SpaceMemberMapper members = mock(SpaceMemberMapper.class);
    private final FileStorageService storage = mock(FileStorageService.class);
    private final DocumentImageReader reader = mock(DocumentImageReader.class);
    private final DocumentImageService service = new DocumentImageService(chunks, documents, members, storage, reader);
    private final LoginUser user = new LoginUser(7L, "test");
    private final DocumentImageReader.Preview image = new DocumentImageReader.Preview(new byte[]{1, 2}, "image/png");

    /** 准备有权限的当前版本分块，不连接数据库或对象存储。 */
    @BeforeEach
    void prepare() throws Exception {
        when(members.selectCount(any())).thenReturn(1L);

        ChunkHitVO chunk = new ChunkHitVO();
        chunk.setImageRef("original");
        when(chunks.findReadableChunk(1L, 10L, 100L, 0)).thenReturn(chunk);

        Document document = new Document();
        document.setSpaceId(1L);
        document.setName("display.png");
        document.setFileType("image/png");
        document.setFilePath("private/owned-original.png");
        when(documents.selectById(10L)).thenReturn(document);
        when(storage.open(BucketType.PRIVATE, document.getFilePath())).thenAnswer(call -> new ByteArrayInputStream(new byte[]{1}));
        when(reader.read(eq("display.png"), eq("image/png"), any(), eq("original"))).thenReturn(image);
    }

    /** 原图路径只来自数据库，且读取前后均校验当前分块。 */
    @Test
    void readsOnlyOwnedOriginalAndRevalidatesSource() {
        assertSame(image, service.read(1L, 10L, 100L, 0, user));
        verify(storage).open(BucketType.PRIVATE, "private/owned-original.png");
        verify(storage, never()).getAccessUrl(any(), anyString(), any());
        verify(chunks, times(2)).findReadableChunk(1L, 10L, 100L, 0);
        verify(members, times(2)).selectCount(any());
    }

    /** 匿名、非成员和旧版本均不能触达文件存储。 */
    @Test
    void rejectsMissingMembershipAndStaleVersionsBeforeStorage() {
        assertThrows(BusinessException.class, () -> service.read(1L, 10L, 100L, 0, null));
        when(members.selectCount(any())).thenReturn(0L);

        assertThrows(BusinessException.class, () -> service.read(1L, 10L, 100L, 0, user));
        when(members.selectCount(any())).thenReturn(1L);

        assertThrows(BusinessException.class, () -> service.read(1L, 10L, 100L, 99, user));
        assertThrows(BusinessException.class, () -> service.read(2L, 10L, 100L, 0, user));
        verify(storage, never()).open(any(), any());
    }

    /** 图片读取期间撤权不能返回已经缓冲的图片字节。 */
    @Test
    void rejectsMembershipRevokedDuringRead() throws Exception {
        when(reader.read(any(), any(), any(), any())).thenAnswer(call -> {
            when(members.selectCount(any())).thenReturn(0L);

            return image;
        });

        assertThrows(BusinessException.class, () -> service.read(1L, 10L, 100L, 0, user));
    }

    /** 图片读取期间版本变化不能返回旧来源。 */
    @Test
    void rejectsSourceChangedDuringRead() throws Exception {
        when(reader.read(any(), any(), any(), any())).thenAnswer(call -> {
            when(chunks.findReadableChunk(1L, 10L, 100L, 0)).thenReturn(null);

            return image;
        });

        assertThrows(BusinessException.class, () -> service.read(1L, 10L, 100L, 0, user));
    }

    /** 失败后释放读取槽，错误中不泄露内部对象路径。 */
    @Test
    void releasesCapacityAfterReadFailure() throws Exception {
        when(reader.read(any(), any(), any(), any())).thenThrow(new IOException("private/secret-key"));

        for (int attempt = 0; attempt < 3; attempt++) {
            BusinessException error = assertThrows(BusinessException.class, () -> service.read(1L, 10L, 100L, 0, user));

            assertFalse(error.getMessage().contains("secret-key"));
            assertTrue(error.getMessage().contains("不可读取"));
        }

        doReturn(image).when(reader).read(any(), any(), any(), any());

        assertSame(image, service.read(1L, 10L, 100L, 0, user));
    }

    /** 两个并发预览占满后立即拒绝第三个，已有预览不受影响。 */
    @Test
    void boundsConcurrentPreviewDecoding() throws Exception {
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        when(reader.read(any(), any(), any(), any())).thenAnswer(call -> {
            entered.countDown();

            assertTrue(release.await(5, TimeUnit.SECONDS));

            return image;
        });

        var executor = Executors.newFixedThreadPool(2);

        try {
            var first = executor.submit(() -> service.read(1L, 10L, 100L, 0, user));
            var second = executor.submit(() -> service.read(1L, 10L, 100L, 0, user));

            assertTrue(entered.await(3, TimeUnit.SECONDS));

            BusinessException error = assertThrows(BusinessException.class, () -> service.read(1L, 10L, 100L, 0, user));

            assertTrue(error.getMessage().contains("繁忙"));
            release.countDown();

            assertSame(image, first.get(3, TimeUnit.SECONDS));
            assertSame(image, second.get(3, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }
}
