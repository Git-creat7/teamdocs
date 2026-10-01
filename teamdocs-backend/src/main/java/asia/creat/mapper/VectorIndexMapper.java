package asia.creat.mapper;

import lombok.Data;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface VectorIndexMapper {
    /**
     * 读取文档当前索引资格。
     * @param documentId 文档ID
     * @return 当前状态，彻底删除时为空
     */
    @Select("SELECT d.id document_id, d.parse_version, "
            + "IF(d.deleted=0 AND s.deleted=0 AND d.parse_status='READY',1,0) ready "
            + "FROM document d LEFT JOIN space s ON s.id=d.space_id WHERE d.id=#{documentId}")
    Snapshot snapshot(@Param("documentId") Long documentId);

    /**
     * 分页扫描文档，包括软删除记录。
     * @param afterId 上次文档ID
     * @return 下一批文档ID
     */
    @Select("SELECT id FROM document WHERE id>#{afterId} ORDER BY id LIMIT 100")
    List<Long> documentIds(@Param("afterId") long afterId);

    /**
     * 查找已彻底删除但仍需清理向量的任务。
     * @return 待清理文档ID
     */
    @Select("SELECT t.document_id FROM document_vector_task t LEFT JOIN document d ON d.id=t.document_id "
            + "WHERE d.id IS NULL AND t.target_signature<>'deleted' ORDER BY t.document_id LIMIT 100")
    List<Long> deletedDocumentIds();

    /**
     * 仅在目标变化时增加代次，避免补扫重复生成向量。
     * @param documentId 文档ID
     * @param signature 目标模型、维度与解析状态
     */
    @Insert("INSERT INTO document_vector_task(document_id,generation,target_signature,state,attempts,next_attempt_ms) "
            + "VALUES(#{documentId},1,#{signature},'PENDING',0,0) ON DUPLICATE KEY UPDATE "
            + "generation=generation+IF(target_signature<>VALUES(target_signature),1,0), "
            + "state=IF(target_signature<>VALUES(target_signature),'PENDING',state), "
            + "attempts=IF(target_signature<>VALUES(target_signature),0,attempts), "
            + "next_attempt_ms=IF(target_signature<>VALUES(target_signature),0,next_attempt_ms), "
            + "error_code=IF(target_signature<>VALUES(target_signature),NULL,error_code), "
            + "target_signature=VALUES(target_signature)")
    void enqueue(@Param("documentId") Long documentId, @Param("signature") String signature);

    /**
     * 读取一个到期任务。
     * @param nowMs 当前时间
     * @return 待处理任务
     */
    @Select("SELECT * FROM document_vector_task WHERE state='PENDING' AND attempts<3 "
            + "AND next_attempt_ms<=#{nowMs} ORDER BY next_attempt_ms,document_id LIMIT 1")
    Task next(@Param("nowMs") long nowMs);

    /**
     * 外部调用前原子记录尝试，崩溃也不丢失次数。
     * @param documentId 文档ID
     * @param generation 任务代次
     * @param nowMs 当前时间
     * @param retryAt 崩溃后的最早重试时间
     * @return 是否领取成功
     */
    @Update("UPDATE document_vector_task SET attempts=attempts+1,next_attempt_ms=#{retryAt} "
            + "WHERE document_id=#{documentId} AND generation=#{generation} AND state='PENDING' "
            + "AND attempts<3 AND next_attempt_ms<=#{nowMs}")
    int reserve(@Param("documentId") Long documentId, @Param("generation") long generation,
                @Param("nowMs") long nowMs, @Param("retryAt") long retryAt);

    /**
     * 检查任务是否被新状态替换。
     * @param documentId 文档ID
     * @return 当前任务
     */
    @Select("SELECT * FROM document_vector_task WHERE document_id=#{documentId}")
    Task task(@Param("documentId") Long documentId);

    /**
     * 按代次完成任务，不覆盖新待办。
     * @param documentId 文档ID
     * @param generation 任务代次
     */
    @Update("UPDATE document_vector_task SET state='DONE',error_code=NULL "
            + "WHERE document_id=#{documentId} AND generation=#{generation} AND state='PENDING'")
    void complete(@Param("documentId") Long documentId, @Param("generation") long generation);

    /**
     * 记录失败并有限重试，不修改文档解析状态。
     * @param documentId 文档ID
     * @param generation 任务代次
     * @param retryAt 最早重试时间
     */
    @Update("UPDATE document_vector_task SET state=IF(attempts>=3,'FAILED','PENDING'), "
            + "next_attempt_ms=#{retryAt},error_code='VECTOR_SYNC_FAILED' "
            + "WHERE document_id=#{documentId} AND generation=#{generation} AND state='PENDING'")
    void fail(@Param("documentId") Long documentId, @Param("generation") long generation,
              @Param("retryAt") long retryAt);

    /**
     * 收敛最后一次尝试期间进程退出的任务。
     * @param nowMs 当前时间
     */
    @Update("UPDATE document_vector_task SET state='FAILED',error_code='ATTEMPTS_EXHAUSTED' "
            + "WHERE state='PENDING' AND attempts>=3 AND next_attempt_ms<=#{nowMs}")
    void expireAttempts(@Param("nowMs") long nowMs);

    @Data
    class Snapshot {
        private Long documentId;
        private Integer parseVersion;
        private boolean ready;
    }

    @Data
    class Task {
        private Long documentId;
        private long generation;
        private String targetSignature;
        private String state;
        private int attempts;
        private long nextAttemptMs;
        private String errorCode;
    }
}
