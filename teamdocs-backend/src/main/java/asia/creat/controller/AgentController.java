package asia.creat.controller;

import asia.creat.agent.AgentData.NewRun;
import asia.creat.agent.AgentData.NewSession;
import asia.creat.agent.AgentService;
import asia.creat.common.Result;
import asia.creat.dto.PageQuery;
import asia.creat.security.LoginUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import asia.creat.common.exception.BusinessException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/spaces/{spaceId}/agent")
@RequiredArgsConstructor
@Slf4j
public class AgentController {
    private final AgentService service;

    @PostMapping("/sessions")
    public Result createSession(@PathVariable Long spaceId, @RequestBody @Validated NewSession request,
                                @AuthenticationPrincipal LoginUser user) {
        return Result.success(service.createSession(spaceId, request, user));
    }
    @GetMapping("/sessions")
    public Result sessions(@PathVariable Long spaceId, @Validated @ModelAttribute PageQuery page,
                           @AuthenticationPrincipal LoginUser user) {
        return Result.success(service.sessions(spaceId, page, user));
    }
    @GetMapping("/sessions/{sessionId}/messages")
    public Result messages(@PathVariable Long spaceId, @PathVariable Long sessionId, @Validated @ModelAttribute PageQuery page,
                           @AuthenticationPrincipal LoginUser user) {
        return Result.success(service.messages(spaceId, sessionId, page, user));
    }
    @PostMapping("/sessions/{sessionId}/runs")
    public Result submit(@PathVariable Long spaceId, @PathVariable Long sessionId, @RequestBody @Validated NewRun request,
                         @AuthenticationPrincipal LoginUser user) {
        return Result.success(Map.of("runId", service.submit(spaceId, sessionId, request, user)));
    }
    @GetMapping("/runs/{runId}")
    public Result run(@PathVariable Long spaceId, @PathVariable Long runId, @AuthenticationPrincipal LoginUser user) {
        return Result.success(service.run(spaceId, runId, user));
    }
    @PostMapping("/runs/{runId}/cancel")
    public Result cancel(@PathVariable Long spaceId, @PathVariable Long runId, @AuthenticationPrincipal LoginUser user) {
        return Result.success(service.cancel(spaceId, runId, user));
    }

    // 全局校验异常日志会包含 rejectedValue；Agent 输入可能是私有问题，不能记录整段请求。
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public Result invalidRequest(Exception ignored) {
        return Result.error("Agent 请求参数无效");
    }

    @ExceptionHandler(BusinessException.class)
    public Result businessFailure(BusinessException error) {
        return Result.error(error.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public Result unavailable(Exception error) {
        log.warn("Agent API 不可用（不记录请求内容）: {}", error.getClass().getSimpleName());
        return Result.error("AI 服务暂时不可用，请稍后重试");
    }
}
