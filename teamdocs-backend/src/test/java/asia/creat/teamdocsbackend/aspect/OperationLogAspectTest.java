package asia.creat.teamdocsbackend.aspect;

import asia.creat.anno.OperationLog;
import asia.creat.anno.SpaceId;
import asia.creat.aspect.OperationLogAspect;
import asia.creat.entity.OperationLogRecord;
import asia.creat.helper.OperationResourceNameResolver;
import asia.creat.security.LoginUser;
import asia.creat.service.OperationLogService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OperationLogAspectTest {
    private static final LoginUser LOGIN_USER = new LoginUser(7L, "alice");

    @Mock
    private OperationLogService operationLogService;

    @Mock
    private OperationResourceNameResolver operationResourceNameResolver;

    @Mock
    private ProceedingJoinPoint joinPoint;

    @Mock
    private MethodSignature signature;

    private OperationLogAspect aspect;

    @BeforeEach
    void setUp() {
        aspect = new OperationLogAspect(operationLogService, operationResourceNameResolver);
    }

    @Test
    void shouldUseReturnValueAsResourceIdWhenExplicitlyEnabled() throws Throwable {
        Method method = LoggedService.class.getMethod("upload", Long.class, LoginUser.class);
        prepareJoinPoint(method, new Object[]{1L, LOGIN_USER}, 88L);

        Object result = aspect.logOperation(joinPoint);

        assertEquals(88L, result);
        OperationLogRecord record = captureSavedRecord();
        assertEquals(88L, record.getResourceId());
        assertEquals(1L, record.getSpaceId());
        assertEquals(7L, record.getUserId());
    }

    @Test
    void shouldIgnoreReturnValueUnlessExplicitlyEnabled() throws Throwable {
        Method method = LoggedService.class.getMethod("withoutResultId", Long.class, LoginUser.class);
        prepareJoinPoint(method, new Object[]{1L, LOGIN_USER}, 99L);

        aspect.logOperation(joinPoint);

        assertNull(captureSavedRecord().getResourceId());
    }

    @Test
    void shouldNotUseResourceIdWhenOperationFails() throws Throwable {
        Method method = LoggedService.class.getMethod("upload", Long.class, LoginUser.class);
        RuntimeException failure = new RuntimeException("upload failed");
        prepareJoinPoint(method, new Object[]{1L, LOGIN_USER}, null);
        when(joinPoint.proceed()).thenThrow(failure);

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> aspect.logOperation(joinPoint)
        );

        assertSame(failure, thrown);
        OperationLogRecord record = captureSavedRecord();
        assertNull(record.getResourceId());
        assertEquals(0, record.getSuccess());
    }

    private void prepareJoinPoint(Method method, Object[] args, Object result) throws Throwable {
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(method);
        when(joinPoint.getArgs()).thenReturn(args);
        when(joinPoint.getTarget()).thenReturn(new LoggedService());
        when(joinPoint.proceed()).thenReturn(result);
    }

    private OperationLogRecord captureSavedRecord() {
        ArgumentCaptor<OperationLogRecord> captor = ArgumentCaptor.forClass(OperationLogRecord.class);
        verify(operationLogService).saveLog(captor.capture());
        return captor.getValue();
    }

    static class LoggedService {
        @OperationLog(
                value = "上传文档",
                resourceType = "DOCUMENT",
                resourceName = "'notes.txt'",
                resourceIdFromResult = true
        )
        public Long upload(@SpaceId Long spaceId, LoginUser loginUser) {
            return 88L;
        }

        @OperationLog(value = "测试操作", resourceType = "DOCUMENT", resourceName = "'notes.txt'")
        public Long withoutResultId(@SpaceId Long spaceId, LoginUser loginUser) {
            return 99L;
        }
    }
}
