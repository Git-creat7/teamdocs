package asia.creat.teamdocsbackend.aspect;

import asia.creat.anno.RequireSpaceRole;
import asia.creat.anno.SpaceId;
import asia.creat.aspect.SpaceRoleAspect;
import asia.creat.common.exception.BusinessException;
import asia.creat.entity.Space;
import asia.creat.entity.SpaceMember;
import asia.creat.entity.SpaceRole;
import asia.creat.mapper.SpaceMapper;
import asia.creat.mapper.SpaceMemberMapper;
import asia.creat.security.LoginUser;
import asia.creat.security.SpaceContext;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpaceRoleAspectTest {
    @Mock
    private SpaceMemberMapper spaceMemberMapper;

    @Mock
    private SpaceMapper spaceMapper;

    @Mock
    private ProceedingJoinPoint joinPoint;

    @Mock
    private MethodSignature signature;

    private SpaceRoleAspect aspect;

    @BeforeEach
    void setUp() {
        aspect = new SpaceRoleAspect(spaceMemberMapper, spaceMapper);
    }

    @AfterEach
    void tearDown() {
        SpaceContext.clear();
    }

    @Test
    void shouldAllowConfiguredRoleAndClearContext() throws Throwable {
        prepareInvocation("ownerOnly", SpaceRole.OWNER);
        when(joinPoint.proceed()).thenAnswer(invocation -> {
            assertEquals(SpaceRole.OWNER, SpaceContext.getSpaceMember().getRole());
            return "ok";
        });

        Object result = aspect.check(joinPoint);

        assertEquals("ok", result);
        assertNull(SpaceContext.getSpaceMember());
    }

    @Test
    void shouldRejectRoleNotDeclaredByAnnotation() throws Throwable {
        prepareInvocation("ownerOnly", SpaceRole.ADMIN);

        assertThrows(BusinessException.class, () -> aspect.check(joinPoint));

        verify(joinPoint, never()).proceed();
        assertNull(SpaceContext.getSpaceMember());
    }

    @Test
    void shouldRejectNonMember() throws Throwable {
        Method method = RestrictedService.class.getMethod("ownerOnly", Long.class, LoginUser.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(method);
        when(joinPoint.getArgs()).thenReturn(new Object[]{1L, new LoginUser(9L, "alice")});
        when(spaceMapper.selectById(1L)).thenReturn(new Space());
        when(spaceMemberMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        assertThrows(BusinessException.class, () -> aspect.check(joinPoint));

        verify(joinPoint, never()).proceed();
    }

    private void prepareInvocation(String methodName, SpaceRole role) throws Exception {
        Method method = RestrictedService.class.getMethod(methodName, Long.class, LoginUser.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(method);
        when(joinPoint.getArgs()).thenReturn(new Object[]{1L, new LoginUser(9L, "alice")});
        when(spaceMapper.selectById(1L)).thenReturn(new Space());

        SpaceMember member = new SpaceMember();
        member.setRole(role);
        when(spaceMemberMapper.selectOne(any(Wrapper.class))).thenReturn(member);
    }

    static class RestrictedService {
        @RequireSpaceRole(SpaceRole.OWNER)
        public void ownerOnly(@SpaceId Long spaceId, LoginUser loginUser) {
        }
    }
}
