package asia.creat.agent;

import asia.creat.anno.RequireSpaceRole;
import asia.creat.anno.SpaceId;
import asia.creat.common.exception.BusinessException;
import asia.creat.security.LoginUser;
import asia.creat.mapper.AnswerFeedbackMapper;
import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AnswerFeedbackService extends ServiceImpl<AnswerFeedbackMapper, AnswerFeedbackService.Feedback> {
    private final AgentRepository runs;
    private final AgentService agent;

    @Data
    @TableName("agent_answer_feedback")
    public static class Feedback {
        @TableId(type = IdType.INPUT) private Long runId;
        private Long userId;
        private String rating;
        private String reason;
    }
    public record Input(@NotBlank @Pattern(regexp="UP|DOWN|NONE") String rating,
                        @Size(max=32) String reason) { }

    @RequireSpaceRole
    public Feedback get(@SpaceId Long spaceId, Long runId, LoginUser user) {
        requireAnswer(spaceId, runId, user);
        return getById(runId);
    }

    @Transactional
    @RequireSpaceRole
    public Feedback submit(@SpaceId Long spaceId, Long runId, Input input, LoginUser user) {
        if (runs.lockUser(user.getUserId()) == null) throw new BusinessException("用户不可用");
        requireAnswer(spaceId, runId, user);
        if (!Set.of("UP", "DOWN", "NONE").contains(input.rating())) throw new BusinessException("反馈类型无效");
        if ("NONE".equals(input.rating())) { removeById(runId); return null; }
        String reason = "DOWN".equals(input.rating()) ? input.reason() : null;
        if (reason != null && !Set.of("WRONG_CITATION", "INCOMPLETE", "NOT_FOUND", "OTHER").contains(reason)) {
            throw new BusinessException("反馈原因无效");
        }
        Feedback row = new Feedback(); row.setRunId(runId); row.setUserId(user.getUserId());
        row.setRating(input.rating()); row.setReason(reason);
        // 显式更新空值，点赞时清除原点踩原因。
        if (getById(runId) == null) save(row);
        else lambdaUpdate().eq(Feedback::getRunId, runId).eq(Feedback::getUserId, user.getUserId())
                .set(Feedback::getRating, row.getRating()).set(Feedback::getReason, row.getReason()).update();
        return row;
    }

    private void requireAnswer(Long spaceId, Long runId, LoginUser user) {
        var run = agent.run(spaceId, runId, user);
        if (!"SUCCEEDED".equals(run.status()) || run.answer() == null || run.answer().masked()) {
            throw new BusinessException("只能评价本人已完成且仍可查看的回答");
        }
    }
}
