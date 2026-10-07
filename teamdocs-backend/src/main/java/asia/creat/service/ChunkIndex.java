package asia.creat.service;

import asia.creat.vo.ChunkIndexHit;
import java.util.List;

/** Elasticsearch 只是可重建索引；读取结果必须回 MySQL 复核。 */
public interface ChunkIndex {
    boolean enabled();

    List<ChunkIndexHit> search(Long spaceId, String keyword, int limit);

    /** 在最终截断前提供有界候选，供混合检索融合与重排。 */
    default List<ChunkIndexHit> searchCandidates(Long spaceId, Long documentId, String keyword, int limit) {
        return search(spaceId, keyword, limit).stream()
                .filter(hit -> documentId == null || documentId.equals(hit.getDocumentId()))
                .toList();
    }

    void syncDocument(Long documentId);

    int rebuild();

    record DocumentStatus(String status, long indexedChunks, String detail) { }

    /** 单文档只读检查，不创建或删除全局索引。 */
    default DocumentStatus documentStatus(Long spaceId, Long documentId, Integer version, long expected) {
        return new DocumentStatus("DISABLED", 0, "关键词索引未启用");
    }
}
