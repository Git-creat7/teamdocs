package asia.creat.controller;

import asia.creat.common.Result;
import asia.creat.common.exception.BusinessException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import asia.creat.memory.UserMemoryData.Edit;
import asia.creat.memory.UserMemoryData.Settings;
import asia.creat.memory.UserMemoryService;
import asia.creat.security.LoginUser;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user/memory")
@RequiredArgsConstructor
@Validated
public class UserMemoryController {
    private final UserMemoryService memory;

    // 校验异常可携带原始记忆文本，不交给会记录完整异常的全局处理器。
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            ConstraintViolationException.class, HandlerMethodValidationException.class})
    public Result invalidRequest(Exception ignored) {
        return Result.error("记忆参数无效，请检查内容长度和版本后重试");
    }

    @ExceptionHandler(BusinessException.class)
    public Result businessFailure(BusinessException error) {
        return Result.error(error.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public Result failure(Exception ignored) {
        return Result.error("记忆暂时不可用，请稍后重试");
    }

    @GetMapping
    public Result get(@AuthenticationPrincipal LoginUser user) {
        return Result.success(memory.view(user.getUserId()));
    }

    @PutMapping("/settings")
    public Result settings(@AuthenticationPrincipal LoginUser user, @RequestBody @Validated Settings request) {
        return Result.success(memory.settings(user.getUserId(), request));
    }

    @PutMapping("/items/{key}")
    public Result edit(@AuthenticationPrincipal LoginUser user, @PathVariable String key,
                       @RequestBody @Validated Edit request) {
        return Result.success(memory.edit(user.getUserId(), key, request));
    }

    @DeleteMapping("/items/{key}")
    public Result delete(@AuthenticationPrincipal LoginUser user, @PathVariable String key,
                         @RequestParam @Min(0) long version) {
        return Result.success(memory.delete(user.getUserId(), key, version));
    }

    @DeleteMapping
    public Result clear(@AuthenticationPrincipal LoginUser user, @RequestParam @Min(0) long version) {
        return Result.success(memory.clear(user.getUserId(), version));
    }
}
