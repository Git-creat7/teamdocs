package asia.creat.common.exception;

import asia.creat.common.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice //全局异常处理器
public class GlobalExceptionHandler {
    //处理全部异常
    @ExceptionHandler
    public Result handleException(Exception e) {
        log.error("发生异常: ", e);
        return Result.error("服务器发生异常，请稍后再试");
    }

    //处理业务自定义异常
    @ExceptionHandler(BusinessException.class)
    public Result handleBusinessException(BusinessException e) {
        log.error("发生业务异常: ", e);
        return Result.error("业务异常：" + e.getMessage());
    }

    //参数为空提示
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        log.error("参数验证失败: ", e);
        //将所有字段错误信息拼接成一个字符串
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(err->err.getField() + ": " + err.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return Result.error("参数验证失败: " + msg);
    }

}
