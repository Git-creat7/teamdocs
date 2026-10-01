package asia.creat.service.impl;

import asia.creat.service.ChunkIndex;
import asia.creat.vo.ChunkIndexHit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
@ConditionalOnProperty(prefix = "teamdocs.elasticsearch", name = "enabled", havingValue = "false", matchIfMissing = true)
public class NoopChunkIndex implements ChunkIndex {
    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public List<ChunkIndexHit> search(Long spaceId, String keyword, int limit) {
        return List.of();
    }

    @Override
    public void syncDocument(Long documentId) {
    }

    @Override
    public int rebuild() {
        throw new IllegalStateException("Elasticsearch 未启用，不能重建索引");
    }
}
