package asia.creat.service;

import asia.creat.entity.DocumentContent;

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
     * 按切片序号升序获取指定文档的所有切片内容
     */
    List<DocumentContent> getChunksByDocumentId(Long documentId);

    /**
     * 彻底物理删除文档时，级联清理所有切片数据
     */
    void purgeByDocumentId(Long documentId);
}
