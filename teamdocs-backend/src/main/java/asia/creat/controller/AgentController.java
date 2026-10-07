package asia.creat.controller;

import asia.creat.agent.AgentData.NewRun;
import asia.creat.agent.AgentData.NewSession;
import asia.creat.agent.AgentEventHub;
import asia.creat.agent.AgentService;
import asia.creat.common.Result;
import asia.creat.common.exception.BusinessException;
import asia.creat.dto.PageQuery;
import asia.creat.security.LoginUser;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/spaces/{spaceId}/agent")
@RequiredArgsConstructor
@Slf4j
public class AgentController {
    private final AgentService service;
    private final AgentEventHub events;

    @ModelAttribute
    public void noCache(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }

    @PostMapping("/sessions")
    public Result createSession(@PathVariable Long spaceId, @RequestBody @Validated NewSession request,
                                @AuthenticationPrincipal LoginUser user) {
        log.info("AgentController 收到创建会话请求: spaceId={}, userId={}", spaceId, user != null ? user.getUserId() : null);

        return Result.success(service.createSession(spaceId, request, user));
    }

    @GetMapping("/sessions")
    public Result sessions(@PathVariable Long spaceId, @Validated @ModelAttribute PageQuery page,
                           @AuthenticationPrincipal LoginUser user) {
        return Result.success(service.sessions(spaceId, page, user));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public Result deleteSession(@PathVariable Long spaceId, @PathVariable Long sessionId,
                                @AuthenticationPrincipal LoginUser user) {
        log.info("AgentController 收到删除会话请求: spaceId={}, sessionId={}", spaceId, sessionId);
        service.deleteSession(spaceId, sessionId, user);

        return Result.success();
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public Result messages(@PathVariable Long spaceId, @PathVariable Long sessionId, @Validated @ModelAttribute PageQuery page,
                           @AuthenticationPrincipal LoginUser user) {
        return Result.success(service.messages(spaceId, sessionId, page, user));
    }

    @PostMapping("/sessions/{sessionId}/runs")
    public Result submit(@PathVariable Long spaceId, @PathVariable Long sessionId, @RequestBody @Validated NewRun request,
                         @AuthenticationPrincipal LoginUser user) {
        log.info("AgentController 收到提问请求: spaceId={}, sessionId={}, clientRequestId={}", spaceId, sessionId, request.clientRequestId());

        return Result.success(Map.of("runId", service.submit(spaceId, sessionId, request, user)));
    }

    @GetMapping("/runs/{runId}")
    public Result run(@PathVariable Long spaceId, @PathVariable Long runId, @AuthenticationPrincipal LoginUser user) {
        return Result.success(service.run(spaceId, runId, user));
    }

    @GetMapping(value = "/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> events(@PathVariable Long spaceId, @PathVariable Long runId, @AuthenticationPrincipal LoginUser user) {
        log.info("AgentController 建立 SSE 连接: spaceId={}, runId={}", spaceId, runId);

        var run = service.run(spaceId, runId, user);
        SseEmitter emitter = events.subscribe(spaceId, run.sessionId(), runId, user.getUserId(), () -> service.run(spaceId, runId, user));

        return ResponseEntity.ok().header("Cache-Control", "no-store").header("X-Accel-Buffering", "no").body(emitter);
    }

    @PostMapping("/runs/{runId}/cancel")
    public Result cancel(@PathVariable Long spaceId, @PathVariable Long runId, @AuthenticationPrincipal LoginUser user) {
        log.info("AgentController 收到取消运行请求: spaceId={}, runId={}", spaceId, runId);

        return Result.success(service.cancel(spaceId, runId, user));
    }

    // 全局校验异常日志会包含 rejectedValue；Agent 输入可能是私有问题，不能记录整段请求。
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Result> invalidRequest(Exception ignored) {
        return jsonError("Agent 请求参数无效");
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result> businessFailure(BusinessException error) {
        log.warn("Agent 业务处理异常: {}", error.getMessage());

        return jsonError(error.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result> unavailable(Exception error) {
        log.warn("Agent API 不可用（不记录请求内容）: {}", error.getClass().getSimpleName());

        return jsonError("AI 服务暂时不可用，请稍后重试");
    }

    private ResponseEntity<Result> jsonError(String message) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(Result.error(message));
    }
}
