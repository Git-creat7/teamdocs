package asia.creat.service;

import asia.creat.entity.DocumentContent;
import asia.creat.entity.ParseStatus;

import java.util.List;

public interface DocumentContentService {

    /**
     * 标记文档正在解析中
     */
    void recordParsing(Long documentId);

    /**
     * 保存文档分块内容并在 document 表中标记状态为 READY
     * （采用先删后插策略，保证同一文档重复解析时的幂等性）
     */
    void saveChunks(Long documentId, Long spaceId, List<DocumentContent> chunks);

    /**
     * 在 document 表中记录文档解析失败状态及原因
     */
    void recordParseFailure(Long documentId, String errorMessage);

    /**
     * 重置 document 表中的解析状态为 PENDING
     */
    void resetParseStatus(Long documentId);

    /**
     * 任务仍是当前 PARSING 版本时替换分块并标为 READY。条件不匹配则不改分块。
     */
    boolean publishIfParsing(Long documentId, Long spaceId, Integer parseVersion, List<DocumentContent> chunks);

    /**
     * 任务仍是当前 PARSING 版本时标为 FAILED 或 SKIPPED。未删除文档会清掉旧分块。
     */
    boolean discardIfParsing(Long documentId, Long spaceId, Integer parseVersion, ParseStatus status, String reason);

    /**
     * 只返回未删除且 READY 文档的切片
     */
    List<DocumentContent> getChunksByDocumentId(Long documentId);

    /**
     * 彻底物理删除文档时，级联清理所有切片数据
     */
    void purgeByDocumentId(Long documentId);
}
