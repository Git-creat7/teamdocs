package asia.creat.controller;

import asia.creat.common.Result;
import asia.creat.common.exception.BusinessException;
import asia.creat.model.AiDependencyHealth;
import asia.creat.model.UserModelData.Edit;
import asia.creat.model.UserModelData;
import asia.creat.model.UserModelService;
import asia.creat.security.LoginUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 所有配置和测试均从登录态确定用户，不接受目标用户ID。 */
@RestController
@RequestMapping("/user/ai")
@RequiredArgsConstructor
public class UserModelController {
    private final UserModelService models;
    private final AiDependencyHealth health;

    @GetMapping("/model")
    public Result get(@AuthenticationPrincipal LoginUser user) { return Result.success(models.view(user.getUserId())); }

    @PutMapping("/model")
    public Result save(@AuthenticationPrincipal LoginUser user, @RequestBody @Valid Edit edit) {
        return Result.success(models.save(user.getUserId(), edit));
    }

    @PostMapping("/model/disable")
    public Result disable(@AuthenticationPrincipal LoginUser user,
                          @RequestBody @Valid UserModelData.Version request) {
        return Result.success(models.disable(user.getUserId(), request.version()));
    }

    @PostMapping("/model/test")
    public Result test(@AuthenticationPrincipal LoginUser user, @RequestBody @Valid Edit edit) {
        return Result.success(health.testDraft(user.getUserId(), edit));
    }

    @GetMapping("/dependencies")
    public Result status(@AuthenticationPrincipal LoginUser user) { return Result.success(health.status(user.getUserId())); }

    @PostMapping("/dependencies/{id}/test")
    public Result probe(@AuthenticationPrincipal LoginUser user, @PathVariable String id) {
        return Result.success(health.test(user.getUserId(), id));
    }

    // 校验异常可能包含 API Key，禁止交给记录完整异常的全局处理器。
    @ExceptionHandler(BusinessException.class)
    public Result business(BusinessException error) { return Result.error(error.getMessage()); }

    @ExceptionHandler(Exception.class)
    public Result invalid(Exception ignored) { return Result.error("AI 配置或请求无效，请检查输入、数据库迁移和服务状态"); }
}
