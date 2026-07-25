package asia.creat.teamdocsbackend.service.impl;

import asia.creat.common.exception.BusinessException;
import asia.creat.entity.User;
import asia.creat.mapper.UserMapper;
import asia.creat.security.LoginUser;
import asia.creat.service.TokenRevocationService;
import asia.creat.service.impl.UserServiceImpl;
import asia.creat.utils.JWTUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {
    private static final Long USER_ID = 7L;
    private static final LoginUser LOGIN_USER = new LoginUser(USER_ID, "alice");

    @Mock
    private JWTUtils jwtUtils;

    @Mock
    private UserMapper userMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private TokenRevocationService tokenRevocationService;

    private UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        userService = new UserServiceImpl(jwtUtils, userMapper, passwordEncoder, tokenRevocationService);
    }

    @Test
    void changePasswordShouldUpdateEncodedPasswordAndInvalidateSessions() {
        User user = activeUser("encoded-old");
        when(userMapper.selectById(USER_ID)).thenReturn(user);
        when(passwordEncoder.matches("old-pass", "encoded-old")).thenReturn(true);
        when(passwordEncoder.matches("new-pass", "encoded-old")).thenReturn(false);
        when(passwordEncoder.encode("new-pass")).thenReturn("encoded-new");
        when(userMapper.updateById(any(User.class))).thenReturn(1);

        userService.changePassword(LOGIN_USER, "old-pass", "new-pass");

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(userCaptor.capture());
        assertEquals("encoded-new", userCaptor.getValue().getPassword());
        verify(passwordEncoder).encode("new-pass");
        verify(tokenRevocationService).invalidateAllForUser(USER_ID);
    }

    @Test
    void changePasswordShouldRejectWrongOldPassword() {
        User user = activeUser("encoded-old");
        when(userMapper.selectById(USER_ID)).thenReturn(user);
        when(passwordEncoder.matches("wrong-old", "encoded-old")).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.changePassword(LOGIN_USER, "wrong-old", "new-pass"));

        assertTrue(ex.getMessage().contains("旧密码"));
        verify(userMapper, never()).updateById(any(User.class));
        verify(passwordEncoder, never()).encode(anyString());
        verify(tokenRevocationService, never()).invalidateAllForUser(any());
    }

    @Test
    void changePasswordShouldRejectSamePassword() {
        User user = activeUser("encoded-old");
        when(userMapper.selectById(USER_ID)).thenReturn(user);
        when(passwordEncoder.matches("same-pass", "encoded-old")).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.changePassword(LOGIN_USER, "same-pass", "same-pass"));

        assertTrue(ex.getMessage().contains("相同"));
        verify(userMapper, never()).updateById(any(User.class));
        verify(passwordEncoder, never()).encode(anyString());
        verify(tokenRevocationService, never()).invalidateAllForUser(any());
    }

    @Test
    void changePasswordShouldRejectDisabledUser() {
        User user = activeUser("encoded-old");
        user.setStatus(0);
        when(userMapper.selectById(USER_ID)).thenReturn(user);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.changePassword(LOGIN_USER, "old-pass", "new-pass"));

        assertTrue(ex.getMessage().contains("禁用"));
        verify(passwordEncoder, never()).matches(anyString(), anyString());
        verify(userMapper, never()).updateById(any(User.class));
        verify(tokenRevocationService, never()).invalidateAllForUser(any());
    }

    @Test
    void changePasswordShouldRollbackWhenSessionInvalidationFails() {
        User user = activeUser("encoded-old");
        when(userMapper.selectById(USER_ID)).thenReturn(user);
        when(passwordEncoder.matches("old-pass", "encoded-old")).thenReturn(true);
        when(passwordEncoder.matches("new-pass", "encoded-old")).thenReturn(false);
        when(passwordEncoder.encode("new-pass")).thenReturn("encoded-new");
        List<String> updatedPasswords = new ArrayList<>();
        when(userMapper.updateById(any(User.class))).thenAnswer(invocation -> {
            User arg = invocation.getArgument(0);
            updatedPasswords.add(arg.getPassword());
            return 1;
        });
        doThrow(new BusinessException("会话失效失败，请稍后重试"))
                .when(tokenRevocationService).invalidateAllForUser(USER_ID);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.changePassword(LOGIN_USER, "old-pass", "new-pass"));

        assertTrue(ex.getMessage().contains("修改密码失败"));
        assertEquals(List.of("encoded-new", "encoded-old"), updatedPasswords);
        assertEquals("encoded-old", user.getPassword());
    }

    @Test
    void changePasswordShouldWarnWhenRollbackAlsoFails() {
        User user = activeUser("encoded-old");
        when(userMapper.selectById(USER_ID)).thenReturn(user);
        when(passwordEncoder.matches("old-pass", "encoded-old")).thenReturn(true);
        when(passwordEncoder.matches("new-pass", "encoded-old")).thenReturn(false);
        when(passwordEncoder.encode("new-pass")).thenReturn("encoded-new");
        when(userMapper.updateById(any(User.class))).thenReturn(1, 0);
        doThrow(new BusinessException("会话失效失败，请稍后重试"))
                .when(tokenRevocationService).invalidateAllForUser(USER_ID);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.changePassword(LOGIN_USER, "old-pass", "new-pass"));

        assertTrue(ex.getMessage().contains("密码已修改但会话失效失败"));
        verify(userMapper, times(2)).updateById(any(User.class));
    }

    private User activeUser(String encodedPassword) {
        User user = new User();
        user.setId(USER_ID);
        user.setUsername("alice");
        user.setPassword(encodedPassword);
        user.setStatus(1);
        return user;
    }
}
