package asia.creat.mapper;

import asia.creat.memory.UserMemoryData.Job;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

import static com.baomidou.mybatisplus.extension.toolkit.ChainWrappers.lambdaUpdateChain;

@Mapper
public interface UserMemoryJobMapper extends BaseMapper<Job> {
    /**
     * 读取到期且仍有访问权限的任务。
     * @param now 当前时间
     * @return 待处理任务
     */
    Job next(@Param("now") long now);

    /**
     * 复核运行状态、用户权限和任务租约。
     * @param id 运行ID
     * @param token 租约令牌
     * @param now 当前时间
     * @return 有效任务，失效时为空
     */
    Job current(@Param("id") long id, @Param("token") String token, @Param("now") long now);

    /**
     * 清理来源已删除、权限失效或重试耗尽的任务。
     * @param now 当前时间
     */
    void discardObsolete(@Param("now") long now);

    /**
     * 原子领取到期任务，保留尝试次数和租约条件。
     * @param id 运行ID
     * @param now 当前时间
     * @param lease 租约截止时间
     * @param token 本次领取令牌
     * @return 是否更新成功
     */
    default boolean claim(long id, long now, long lease, String token) {
        return lambdaUpdateChain(this)
                .eq(Job::getRunId, id).lt(Job::getAttempts, 3)
                .and(query -> query.eq(Job::getStatus, "PENDING").le(Job::getNextAttemptMs, now)
                        .or(or -> or.eq(Job::getStatus, "RUNNING").le(Job::getLeaseUntilMs, now)))
                .set(Job::getStatus, "RUNNING").setIncrBy(Job::getAttempts, 1)
                .set(Job::getClaimToken, token).set(Job::getLeaseUntilMs, lease).update();
    }

    /**
     * 仅允许当前租约持有者完成任务。
     * @param id 运行ID
     * @param token 租约令牌
     * @param now 当前时间
     * @return 是否更新成功
     */
    default boolean complete(long id, String token, long now) {
        return lambdaUpdateChain(this)
                .eq(Job::getRunId, id).eq(Job::getStatus, "RUNNING")
                .eq(Job::getClaimToken, token).gt(Job::getLeaseUntilMs, now)
                .set(Job::getStatus, "DONE").set(Job::getClaimToken, null).update();
    }

    /**
     * 作废用户操作之前登记的任务。
     * @param userId 用户ID
     */
    default void discard(long userId) {
        lambdaUpdateChain(this)
                .eq(Job::getUserId, userId).in(Job::getStatus, "PENDING", "RUNNING")
                .set(Job::getStatus, "DISCARDED").set(Job::getClaimToken, null).update();
    }

    /**
     * 按领取时的尝试次数安排重试，令牌不匹配时不更新。
     * @param job 已领取任务
     * @param next 下次尝试时间
     */
    default void fail(Job job, long next) {
        lambdaUpdateChain(this)
                .eq(Job::getRunId, job.getRunId()).eq(Job::getStatus, "RUNNING")
                .eq(Job::getClaimToken, job.getClaimToken())
                .set(Job::getStatus, job.getAttempts() >= 3 ? "FAILED" : "PENDING")
                .set(Job::getClaimToken, null).set(Job::getNextAttemptMs, next).update();
    }

    /** 清理七天前的终态记录，每次最多删除200条。 */
    default void prune() {
        lambdaUpdateChain(this)
                .in(Job::getStatus, "DONE", "FAILED", "DISCARDED")
                .lt(Job::getCreatedAt, LocalDateTime.now().minusDays(7)).last("LIMIT 200").remove();
    }
}
