package asia.creat.service;

import asia.creat.anno.RequireSpaceRole;
import asia.creat.anno.SpaceId;
import asia.creat.common.BucketType;
import asia.creat.common.exception.BusinessException;
import asia.creat.entity.Document;
import asia.creat.entity.SpaceMember;
import asia.creat.mapper.DocumentContentMapper;
import asia.creat.mapper.DocumentMapper;
import asia.creat.mapper.SpaceMemberMapper;
import asia.creat.parse.DocumentImageReader;
import asia.creat.security.LoginUser;
import asia.creat.vo.ChunkHitVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.Semaphore;

import static com.baomidou.mybatisplus.extension.toolkit.ChainWrappers.lambdaQueryChain;

@Service
@RequiredArgsConstructor
public class DocumentImageService {
    private final DocumentContentMapper chunks;
    private final DocumentMapper documents;
    private final SpaceMemberMapper members;
    private final FileStorageService storage;
    private final DocumentImageReader reader;
    private final Semaphore slots = new Semaphore(2);

    /**
     * 读取已授权分块对应的原图，不接受客户端指定对象路径。
     * @param spaceId 空间ID
     * @param documentId 文档ID
     * @param chunkId 来源分块ID
     * @param parseVersion 来源解析版本
     * @param user 当前用户
     * @return 有界图片内容
     */
    @RequireSpaceRole
    public DocumentImageReader.Preview read(@SpaceId Long spaceId, Long documentId, Long chunkId,
                                            Integer parseVersion, LoginUser user) {
        ChunkHitVO source = readable(spaceId, documentId, chunkId, parseVersion, user);
        Document document = documents.selectById(documentId);

        if (document == null || !Objects.equals(document.getSpaceId(), spaceId)) throw inaccessible();

        if (!slots.tryAcquire()) throw new BusinessException("图片预览繁忙，请稍后再试");

        try (InputStream input = storage.open(BucketType.PRIVATE, document.getFilePath())) {
            DocumentImageReader.Preview image = reader.read(document.getName(), document.getFileType(), input, source.getImageRef());
            ChunkHitVO latest = readable(spaceId, documentId, chunkId, parseVersion, user);

            if (!Objects.equals(source.getImageRef(), latest.getImageRef())) throw inaccessible();

            return image;
        } catch (IOException e) {
            throw new BusinessException("图片不可读取或超过预览限制");
        } finally {
            slots.release();
        }
    }

    /** 在读取前后校验成员资格、READY 状态与同一解析版本。 */
    private ChunkHitVO readable(Long spaceId, Long documentId, Long chunkId, Integer version, LoginUser user) {
        if (user == null || version == null || version < 0
                || lambdaQueryChain(members)
                        .eq(SpaceMember::getSpaceId, spaceId).eq(SpaceMember::getUserId, user.getUserId()).count() == 0) {
            throw inaccessible();
        }

        ChunkHitVO source = chunks.findReadableChunk(spaceId, documentId, chunkId, version);

        if (source == null || !source.isImageSource()) throw inaccessible();

        return source;
    }

    /** 使用固定错误，不向调用方泄露内部存储路径。 */
    private BusinessException inaccessible() {
        return new BusinessException("原图已更新或不可访问");
    }
}
