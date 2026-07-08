package asia.creat.aspect;

import asia.creat.anno.RequireSpaceRole;
import asia.creat.common.exception.BusinessException;
import asia.creat.entity.Space;
import asia.creat.entity.SpaceMember;
import asia.creat.entity.SpaceRole;
import asia.creat.mapper.SpaceMapper;
import asia.creat.mapper.SpaceMemberMapper;
import asia.creat.security.LoginUser;
import asia.creat.security.SpaceContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Arrays;


@Slf4j
@Component
@Aspect
public class SpaceRoleAspect {
    /*
    * 该切面用于处理与空间角色相关的权限控制
    */
    private final SpaceMemberMapper spaceMemberMapper;
    private final SpaceMapper spaceMapper;

    public SpaceRoleAspect(SpaceMemberMapper spaceMemberMapper, SpaceMapper spaceMapper) {
        this.spaceMemberMapper = spaceMemberMapper;
        this.spaceMapper = spaceMapper;
    }

    //环绕通知，拦截所有使用了@RequireSpaceRole注解的方法
    @Around("@annotation(asia.creat.anno.RequireSpaceRole)")
    public Object check(ProceedingJoinPoint pjp) throws Throwable {
        //获取签名
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        //获取方法上的@RequireSpaceRole注解
        Method method = signature.getMethod();
        RequireSpaceRole requireSpaceRole = method.getAnnotation(RequireSpaceRole.class);
        //拿到注解中的角色
        SpaceRole[] roles = requireSpaceRole.value();
        if (roles == null || roles.length == 0) {
            roles = new SpaceRole[]{SpaceRole.OWNER, SpaceRole.ADMIN, SpaceRole.MEMBER};
        }

        //遍历参数
        Object[] args = pjp.getArgs();
        Annotation[][] parameterAnnotations = method.getParameterAnnotations();
        Long spaceId = null;
        LoginUser loginUser = null;
        for (int i = 0; i < args.length; i++) {
            for (Annotation annotation : parameterAnnotations[i]) {
                if (annotation instanceof asia.creat.anno.SpaceId) {
                    spaceId = (Long) args[i];
                    break;
                }
            }
            if (args[i] instanceof LoginUser) {
                loginUser = (LoginUser) args[i];
            }
        }
        if (spaceId == null || loginUser == null) {
            throw new IllegalStateException("未找到空间ID或登录用户");
        }

        //检查空间是否存在
        Space space = spaceMapper.selectById(spaceId);
        if (space == null) {
            throw new BusinessException("空间不存在");
        }
        //检查用户在该空间的角色
        SpaceMember member = spaceMemberMapper.selectOne(
                new LambdaQueryWrapper<SpaceMember>()
                        .eq(SpaceMember::getSpaceId, spaceId)
                        .eq(SpaceMember::getUserId, loginUser.getUserId())
        );

        if (member == null)
            throw new BusinessException("您不是该空间成员");

        if (!Arrays.asList(roles).contains(member.getRole()))
            throw new BusinessException("您没有权限操作该空间");

        SpaceContext.set(member);
        try {
            return pjp.proceed();
        } finally {
            SpaceContext.clear();
        }
    }

}
