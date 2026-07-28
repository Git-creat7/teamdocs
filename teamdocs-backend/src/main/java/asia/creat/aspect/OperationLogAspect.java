package asia.creat.aspect;

import asia.creat.anno.OperationLog;
import asia.creat.anno.OperationTarget;
import asia.creat.anno.SpaceId;
import asia.creat.entity.OperationLogRecord;
import asia.creat.helper.OperationResourceNameResolver;
import asia.creat.security.LoginUser;
import asia.creat.service.OperationLogService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.Order;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;


import java.lang.annotation.Annotation;
import java.lang.reflect.Method;



@Component
@Aspect
@Slf4j
@Order(1) //设置切面执行顺序
public class OperationLogAspect {
    /*
    * 该切面用于记录操作日志
    * */

    private final OperationLogService operationLogService;
    private final OperationResourceNameResolver operationResourceNameResolver;

    private final ExpressionParser spelParser = new SpelExpressionParser();
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    public OperationLogAspect(OperationLogService operationLogService,OperationResourceNameResolver operationResourceNameResolver) {
        this.operationLogService = operationLogService;
        this.operationResourceNameResolver = operationResourceNameResolver;
    }

    private String evalResourceName(String expression, Method method, Object[] args) {
        if (expression == null || expression.isBlank()) {
            return null;
        }
        try {
            StandardEvaluationContext context = new StandardEvaluationContext();
            String[] paramNames = parameterNameDiscoverer.getParameterNames(method);
            for (int i = 0; i < args.length; i++) {
                String name = (paramNames != null && i < paramNames.length) ? paramNames[i] : "p" + i;
                context.setVariable(name, args[i]);
            }
            Object value = spelParser.parseExpression(expression).getValue(context);
            if (value == null) {
                return null;
            }
            String text = value.toString();
            return text.length() > 255 ? text.substring(0, 255) : text;
        } catch (Exception e) {
            log.warn("操作日志: resourceName 表达式求值失败: {}", expression, e);
            return null;
        }
    }

    //环绕通知，拦截所有使用了@OperationLog注解的方法
    @Around("@annotation(asia.creat.anno.OperationLog)")
    public Object logOperation(ProceedingJoinPoint pjp)throws Throwable {
        //获取方法签名
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        //获取方法上的@OperationLog注解
        Method method = signature.getMethod();
        OperationLog operationLog = method.getAnnotation(OperationLog.class);
        //获取操作名称和资源类型
        String operationName = operationLog.value();
        String resourceType = operationLog.resourceType();
        String resourceName = evalResourceName(operationLog.resourceName(), method, pjp.getArgs());


        //遍历参数
        Object[] args = pjp.getArgs();
        Annotation[][] parameterAnnotations = method.getParameterAnnotations();
        Long userId = null;
        Long spaceId = null;
        Long resourceId = null;
        String requestMethod = null;
        String requestUri = null;



        for(int i = 0; i < args.length; i++){
            if(args[i] instanceof LoginUser) {
                userId = ((LoginUser) args[i]).getUserId();
            }

            for(Annotation annotation : parameterAnnotations[i]){
                if(annotation instanceof SpaceId && args[i] instanceof Long) {
                    spaceId = (Long) args[i];
                } else if(annotation instanceof OperationTarget && args[i] instanceof Long) {
                    resourceId = (Long) args[i];
                }
            }
        }

        //解析资源名称
        if (resourceName == null || resourceName.isBlank()) {
            resourceName = operationResourceNameResolver    .resolve(resourceType, resourceId, spaceId);
        }


        //获取请求方法和请求URI
        var requestAttributes = RequestContextHolder.getRequestAttributes();
        if (requestAttributes instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            requestMethod = request.getMethod();
            requestUri = request.getRequestURI();
        }

        String methodName = pjp.getTarget().getClass()
                .getSimpleName() + "." + method.getName();



        //计时
        long startTime = System.currentTimeMillis();
        int success = 1;
        String errorMessage = null;

        try{
            return pjp.proceed();
        }catch (Throwable throwable){
            success = 0;
            errorMessage = throwable.getMessage();
            log.error("操作日志: 操作名称={}, 资源类型={}, 方法执行异常: {}", operationName, resourceType, errorMessage);
            throw throwable;
        }finally {
            if(userId == null) {
                log.warn("操作日志: 操作名称={}, 资源类型={}, 未找到登录用户", operationName, resourceType);
            }else {

                long endTime = System.currentTimeMillis();
                long duration = endTime - startTime;
                if (errorMessage != null) {
                    errorMessage = errorMessage.length() > 512
                            ? errorMessage.substring(0, 512)
                            : errorMessage;
                }
                //记录日志
                log.debug("操作日志: 操作名称={}, 资源类型={}, 耗时={}ms", operationName, resourceType, duration);

                OperationLogRecord operationLogRecord = new OperationLogRecord();
                operationLogRecord.setOperationName(operationName);
                operationLogRecord.setResourceType(resourceType);
                operationLogRecord.setUserId(userId);
                operationLogRecord.setSpaceId(spaceId);
                operationLogRecord.setResourceId(resourceId);
                operationLogRecord.setResourceName(resourceName);
                operationLogRecord.setMethodName(methodName);
                operationLogRecord.setRequestMethod(requestMethod);
                operationLogRecord.setRequestUri(requestUri);
                operationLogRecord.setDurationMs(duration);
                operationLogRecord.setSuccess(success);

                operationLogRecord.setErrorMessage(errorMessage);

                try {
                    operationLogService.saveLog(operationLogRecord);
                    log.info("操作日志: 保存日志成功: {}", operationLogRecord);
                } catch (Exception e) {
                    log.error("操作日志: 保存日志失败: {}", operationLogRecord, e);
                }
            }
        }


    }
}
