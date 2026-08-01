package asia.creat.aspect;

import asia.creat.anno.RequireSpaceRole;
import asia.creat.anno.SpaceId;
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
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Arrays;

@Slf4j
@Component
@Aspect
@RequiredArgsConstructor
public class SpaceRoleAspect {
    private final SpaceMemberMapper spaceMemberMapper;
    private final SpaceMapper spaceMapper;

    @Around("@annotation(asia.creat.anno.RequireSpaceRole)")
    public Object check(ProceedingJoinPoint pjp) throws Throwable {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();
        RequireSpaceRole requireSpaceRole = method.getAnnotation(RequireSpaceRole.class);
        SpaceRole[] roles = requireSpaceRole.value();
        if (roles == null || roles.length == 0) {
            roles = new SpaceRole[]{SpaceRole.OWNER, SpaceRole.ADMIN, SpaceRole.MEMBER};
        }

        Object[] args = pjp.getArgs();
        Annotation[][] parameterAnnotations = method.getParameterAnnotations();
        Long spaceId = null;
        LoginUser loginUser = null;
        for (int i = 0; i < args.length; i++) {
            for (Annotation annotation : parameterAnnotations[i]) {
                if (annotation instanceof SpaceId) {
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

        Space space = spaceMapper.selectById(spaceId);
        if (space == null) {
            throw new BusinessException("空间不存在");
        }
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
