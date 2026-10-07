package asia.creat.retrieval;

import asia.creat.config.EmbeddingProperties;
import asia.creat.config.MilvusProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import asia.creat.mapper.VectorIndexMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "teamdocs.milvus", name = "enabled", havingValue = "true", matchIfMissing = true)
public class VectorIndexQueue {
    private final VectorIndexMapper mapper;
    private final EmbeddingProperties properties;
    private final MilvusProperties milvus;

    /**
     * 登记文档当前目标，可参与调用方业务事务。
     * @param documentId 文档ID
     */
    public void enqueue(Long documentId) {
        if (!properties.isEnabled() || !properties.isAllowDocumentEgress()
                || !RetrievalHttp.hasApiKey(properties.getApiKey())) {
            return;
        }

        mapper.enqueue(documentId, signature(mapper.snapshot(documentId)));
    }

    /**
     * 生成索引目标标识，模型或维度变化也会触发重建。
     * @param snapshot 当前文档状态
     * @return 不包含正文的目标标识
     */
    public String signature(VectorIndexMapper.Snapshot snapshot) {
        if (snapshot == null) {
            return "deleted";
        }

        String target = "v1|" + properties.getModelName() + "|" + properties.getDimensions() + "|"
                + milvus.getUrl() + "|" + milvus.getCollection() + "|"
                + snapshot.getParseVersion() + "|" + snapshot.isReady();

        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(target.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
