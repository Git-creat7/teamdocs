package asia.creat.service;

import asia.creat.security.LoginUser;
import asia.creat.vo.ChunkCitationVO;
import asia.creat.vo.ChunkHitVO;
import asia.creat.vo.ChunkReadVO;

import java.util.List;

public interface DocumentChunkQueryService {

    List<ChunkHitVO> searchChunksInDocument(Long spaceId, Long documentId, String keyword, LoginUser loginUser);

    /**
     * 在当前空间检索 READY 正文。不替代原来的文档名搜索。
     */
    List<ChunkHitVO> searchChunks(Long spaceId, String keyword, LoginUser loginUser);

    /**
     * 按切片顺序读取完整分块。只有当前 READY 版本可读，其他状态通过 parseStatus 说明原因。
     */
    ChunkReadVO readChunks(Long spaceId, Long documentId, int startIndex, int limit, LoginUser loginUser);

    /**
     * 按解析版本取回引用。版本、删除或权限对不上时不返回正文。
     */
    ChunkCitationVO resolveCitation(Long spaceId, Long documentId, Long chunkId, Integer parseVersion, LoginUser loginUser);
}
