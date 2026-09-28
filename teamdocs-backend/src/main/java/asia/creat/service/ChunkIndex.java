package asia.creat.service;

import asia.creat.vo.ChunkIndexHit;
import java.util.List;

/** Elasticsearch 只是可重建索引；读取结果必须回 MySQL 复核。 */
public interface ChunkIndex {
    boolean enabled();
    List<ChunkIndexHit> search(Long spaceId, String keyword, int limit);
    void syncDocument(Long documentId);
    int rebuild();
}
