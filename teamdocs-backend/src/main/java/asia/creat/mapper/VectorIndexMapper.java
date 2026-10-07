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
                .orderByAsc(Task::getNextAttemptMs, Task::getDocumentId).last("LIMIT 1").one();
    }

    /**
     * 外部调用前原子记录尝试，崩溃也不丢失次数。
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
     * 按代次完成任务，不覆盖新待办。
     * @param documentId 文档ID
     * @param generation 任务代次
     */
    default void complete(Long documentId, long generation) {
        lambdaUpdateChain(this)
                .eq(Task::getDocumentId, documentId).eq(Task::getGeneration, generation).eq(Task::getState, "PENDING")
                .set(Task::getState, "DONE").set(Task::getErrorCode, null).update();
    }

    /**
     * 记录失败并有限重试，不修改文档解析状态。
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
        private String state;
        private int attempts;
        private long nextAttemptMs;
        private String errorCode;
    }
}
