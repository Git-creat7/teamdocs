package asia.creat.controller;

import asia.creat.agent.AgentScopeService;
import asia.creat.agent.AnswerFeedbackService;
import asia.creat.common.Result;
import asia.creat.security.LoginUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/spaces/{spaceId}/agent")
@RequiredArgsConstructor
public class AgentOptionsController {
    private final AgentScopeService scopes;
    private final AnswerFeedbackService feedback;

    @GetMapping("/scope-options")
    public Result options(@PathVariable Long spaceId, @RequestParam(defaultValue="0") Long folderId,
                          @AuthenticationPrincipal LoginUser user) {
        return Result.success(scopes.options(spaceId, folderId, user));
    }
    @GetMapping("/runs/{runId}/feedback")
    public Result feedback(@PathVariable Long spaceId, @PathVariable Long runId, @AuthenticationPrincipal LoginUser user) {
        return Result.success(feedback.get(spaceId, runId, user));
    }
    @PutMapping("/runs/{runId}/feedback")
    public Result feedback(@PathVariable Long spaceId, @PathVariable Long runId,
                           @RequestBody @Valid AnswerFeedbackService.Input input, @AuthenticationPrincipal LoginUser user) {
        return Result.success(feedback.submit(spaceId, runId, input, user));
    }
}
