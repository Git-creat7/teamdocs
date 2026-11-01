package asia.creat.mapper;

import lombok.Data;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Objects;

import static com.baomidou.mybatisplus.extension.toolkit.ChainWrappers.lambdaQueryChain;
import static com.baomidou.mybatisplus.extension.toolkit.ChainWrappers.lambdaUpdateChain;

@Mapper
public interface VectorIndexMapper extends BaseMapper<VectorIndexMapper.Task> {
    /**
     * 读取文档当前索引资格。
     * @param documentId 文档ID
     * @return 当前状态，彻底删除时为空
     */
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
    List<Long> deletedDocumentIds();

    /**
     * 查找已完成切换但仍需补做旧向量清理的任务。
     * @return 待清理任务
     */
    @Select("SELECT document_id, generation, target_signature, target_parse_version, state, attempts, next_attempt_ms, lease_until_ms, claim_token, active_generation, active_parse_version, active_collection, stale_collection, active_chunk_count, cleanup_pending, error_code FROM document_vector_task WHERE cleanup_pending=1 LIMIT 100")
    List<Task> pendingCleanupTasks();

    /**
     * 查询指定空间内所有已激活向量索引的文档任务，用于蓝绿集合路由。
     * @param spaceId 空间ID
     * @return 已激活任务列表
     */
    @Select("SELECT t.document_id, t.generation, t.target_signature, t.target_parse_version, t.state, t.attempts, t.next_attempt_ms, t.lease_until_ms, t.claim_token, t.active_generation, t.active_parse_version, t.active_collection, t.stale_collection, t.active_chunk_count, t.cleanup_pending, t.error_code FROM document_vector_task t INNER JOIN document d ON d.id=t.document_id WHERE d.space_id=#{spaceId} AND d.deleted=0 AND d.parse_status='READY' AND t.active_generation>0 ORDER BY t.document_id")
    List<Task> activeTasksBySpace(@Param("spaceId") Long spaceId);

    /**
     * 条件原子切换代次、有效集合与版本（XML 实现）。
     */
    int completeAndSwitchRow(@Param("documentId") Long documentId,
                             @Param("generation") long generation,
                             @Param("claimToken") String claimToken,
                             @Param("nowMs") long nowMs,
                             @Param("targetParseVersion") Integer targetParseVersion,
                             @Param("activeCollection") String activeCollection,
                             @Param("writtenCount") int writtenCount);

    /**
     * 仅在目标变化时增加代次，避免补扫重复生成向量。
     * @param documentId 文档ID
     * @param signature 目标模型、维度与解析状态
     */
    void enqueue(@Param("documentId") Long documentId, @Param("signature") String signature);

    /**
     * 读取一个到期任务。
     * @param nowMs 当前时间
     * @return 待处理任务
     */
    default Task next(long nowMs) {
        return lambdaQueryChain(this)
                .eq(Task::getState, "PENDING").lt(Task::getAttempts, 3).le(Task::getNextAttemptMs, nowMs)
                .le(Task::getLeaseUntilMs, nowMs)
                .orderByAsc(Task::getNextAttemptMs, Task::getDocumentId).last("LIMIT 1").one();
    }

    /**
     * 带 claimToken 与租约的原子领取。
     * @param documentId 文档ID
     * @param generation 任务代次
     * @param claimToken 领取凭证
     * @param nowMs 当前时间
     * @param leaseUntilMs 租约截止时间
     * @return 是否领取成功
     */
    default boolean reserve(Long documentId, long generation, String claimToken, long nowMs, long leaseUntilMs) {
        return lambdaUpdateChain(this)
                .eq(Task::getDocumentId, documentId)
                .eq(Task::getGeneration, generation)
                .eq(Task::getState, "PENDING")
                .lt(Task::getAttempts, 3)
                .le(Task::getNextAttemptMs, nowMs)
                .le(Task::getLeaseUntilMs, nowMs)
                .setIncrBy(Task::getAttempts, 1)
                .set(Task::getClaimToken, claimToken)
                .set(Task::getLeaseUntilMs, leaseUntilMs)
                .set(Task::getNextAttemptMs, leaseUntilMs)
                .update();
    }

    /**
     * 外部调用前原子记录尝试，崩溃也不丢失次数（兼容保留）。
     * @param documentId 文档ID
     * @param generation 任务代次
     * @param nowMs 当前时间
     * @param retryAt 崩溃后的最早重试时间
     * @return 是否领取成功
     */
    default boolean reserve(Long documentId, long generation, long nowMs, long retryAt) {
        return lambdaUpdateChain(this)
                .eq(Task::getDocumentId, documentId).eq(Task::getGeneration, generation)
                .eq(Task::getState, "PENDING").lt(Task::getAttempts, 3).le(Task::getNextAttemptMs, nowMs)
                .setIncrBy(Task::getAttempts, 1).set(Task::getNextAttemptMs, retryAt).update();
    }

    /**
     * 检查任务是否被新状态替换。
     * @param documentId 文档ID
     * @return 当前任务
     */
    default Task task(Long documentId) {
        return selectById(documentId);
    }

    /**
     * 校验当前任务是否仍由持有 claimToken 的 Worker 拥有且租约有效。
     * @param documentId 文档ID
     * @param generation 任务代次
     * @param claimToken 领取凭证
     * @param nowMs 当前时间
     * @return 是否有效
     */
    default boolean verifyCurrent(Long documentId, long generation, String claimToken, long nowMs) {
        Task current = selectById(documentId);
        return current != null
                && current.getGeneration() == generation
                && "PENDING".equals(current.getState())
                && Objects.equals(current.getClaimToken(), claimToken)
                && current.getLeaseUntilMs() > nowMs
                && current.getActiveGeneration() < generation;
    }

    /**
     * 条件原子切换代次、有效集合与版本。
     * @param documentId 文档ID
     * @param generation 任务代次
     * @param claimToken 领取凭证
     * @param nowMs 当前时间
     * @param targetParseVersion 目标解析版本
     * @param activeCollection 新有效集合名称
     * @param writtenCount 实际写入向量数量
     * @return 是否切换成功
     */
    default boolean completeAndSwitch(Long documentId, long generation, String claimToken, long nowMs,
                                      Integer targetParseVersion, String activeCollection, int writtenCount) {
        if (claimToken == null || claimToken.isBlank()) {
            return false;
        }
        return completeAndSwitchRow(documentId, generation, claimToken, nowMs,
                targetParseVersion, activeCollection, writtenCount) > 0;
    }

    /**
     * 条件原子切换代次与版本（兼容重载）。
     */
    default boolean completeAndSwitch(Long documentId, long generation, String claimToken, long nowMs,
                                      Integer targetParseVersion, int writtenCount) {
        return completeAndSwitch(documentId, generation, claimToken, nowMs, targetParseVersion, null, writtenCount);
    }

    /**
     * 清理残留向量后重置清理挂起标志与旧集合引用。
     * @param documentId 文档ID
     * @param generation 任务代次
     * @return 是否清除成功
     */
    default boolean clearCleanupPending(Long documentId, long generation) {
        return lambdaUpdateChain(this)
                .eq(Task::getDocumentId, documentId)
                .eq(Task::getActiveGeneration, generation)
                .set(Task::getCleanupPending, 0)
                .set(Task::getStaleCollection, null)
                .update();
    }

    /**
     * 按代次完成任务，不覆盖新待办（兼容保留）。
     * @param documentId 文档ID
     * @param generation 任务代次
     */
    default void complete(Long documentId, long generation) {
        lambdaUpdateChain(this)
                .eq(Task::getDocumentId, documentId).eq(Task::getGeneration, generation).eq(Task::getState, "PENDING")
                .set(Task::getState, "DONE").set(Task::getErrorCode, null).update();
    }

    /**
     * 带代次、claimToken、状态与租约有效期校验的失败记录与退避重试。
     * @param documentId 文档ID
     * @param generation 任务代次
     * @param claimToken 领取凭证
     * @param nowMs 当前时间（校验租约未过期）
     * @param retryAt 最早重试时间
     * @return 是否成功更新失败状态
     */
    default boolean fail(Long documentId, long generation, String claimToken, long nowMs, long retryAt) {
        if (claimToken == null || claimToken.isBlank()) {
            return false;
        }
        return lambdaUpdateChain(this)
                .eq(Task::getDocumentId, documentId)
                .eq(Task::getGeneration, generation)
                .eq(Task::getState, "PENDING")
                .eq(Task::getClaimToken, claimToken)
                .gt(Task::getLeaseUntilMs, nowMs)
                .setSql("state=IF(attempts>=3,'FAILED','PENDING')")
                .set(Task::getNextAttemptMs, retryAt)
                .set(Task::getLeaseUntilMs, 0L)
                .set(Task::getClaimToken, null)
                .set(Task::getErrorCode, "VECTOR_SYNC_FAILED")
                .update();
    }

    /**
     * 带 claimToken 与租约校验的失败记录与退避重试（使用当前系统时间校验租约）。
     * @param documentId 文档ID
     * @param generation 任务代次
     * @param claimToken 领取凭证
     * @param retryAt 最早重试时间
     * @return 是否成功更新失败状态
     */
    default boolean fail(Long documentId, long generation, String claimToken, long retryAt) {
        return fail(documentId, generation, claimToken, System.currentTimeMillis(), retryAt);
    }

    /**
     * 记录失败并有限重试，不修改文档解析状态（兼容保留）。
     * @param documentId 文档ID
     * @param generation 任务代次
     * @param retryAt 最早重试时间
     */
    default void fail(Long documentId, long generation, long retryAt) {
        lambdaUpdateChain(this)
                .eq(Task::getDocumentId, documentId).eq(Task::getGeneration, generation).eq(Task::getState, "PENDING")
                .setSql("state=IF(attempts>=3,'FAILED','PENDING')")
                .set(Task::getNextAttemptMs, retryAt).set(Task::getErrorCode, "VECTOR_SYNC_FAILED").update();
    }

    /**
     * 收敛最后一次尝试期间进程退出的任务。
     * @param nowMs 当前时间
     */
    default void expireAttempts(long nowMs) {
        lambdaUpdateChain(this)
                .eq(Task::getState, "PENDING").ge(Task::getAttempts, 3).le(Task::getNextAttemptMs, nowMs)
                .le(Task::getLeaseUntilMs, nowMs)
                .set(Task::getState, "FAILED").set(Task::getErrorCode, "ATTEMPTS_EXHAUSTED").update();
    }

    @Data
    class Snapshot {
        private Long documentId;
        private Integer parseVersion;
        private boolean ready;
    }

    @Data
    @TableName("document_vector_task")
    class Task {
        @TableId(type = IdType.INPUT)
        private Long documentId;
        private long generation;
        private String targetSignature;
        private Integer targetParseVersion;
        private String state;
        private int attempts;
        private long nextAttemptMs;
        private long leaseUntilMs;
        private String claimToken;
        private long activeGeneration;
        private Integer activeParseVersion;
        private String activeCollection;
        private String staleCollection;
        private int activeChunkCount;
        private int cleanupPending;
        private String errorCode;
    }
}
