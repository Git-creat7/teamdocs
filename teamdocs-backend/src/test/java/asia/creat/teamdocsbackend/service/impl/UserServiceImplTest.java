package asia.creat.teamdocsbackend.service.impl;

import asia.creat.common.BucketType;
import asia.creat.common.exception.BusinessException;
import asia.creat.config.MinioProperties;
import asia.creat.dto.UpdateProfileDTO;
import asia.creat.entity.User;
import asia.creat.mapper.UserMapper;
import asia.creat.security.LoginUser;
import asia.creat.service.FileStorageService;
import asia.creat.service.TokenRevocationService;
import asia.creat.service.impl.UserServiceImpl;
import asia.creat.utils.JWTUtils;
import asia.creat.vo.LoginResultVO;
import asia.creat.vo.UserProfileVO;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {
    private static final Long USER_ID = 7L;
    private static final LoginUser LOGIN_USER = new LoginUser(USER_ID, "alice");
    private static final String PUBLIC_ENDPOINT = "http://localhost:9000";
    private static final String PUBLIC_BUCKET = "teamdocs-public";
    // 中性夹具名，避免密钥扫描把 mock 字面量当真实凭据
    private static final String HASH_STORED = "hash-stored";
    private static final String HASH_PREV = "hash-prev";
    private static final String HASH_NEXT = "hash-next";
    private static final String PLAIN_INPUT = "plain-input";
    private static final String PLAIN_WRONG = "plain-wrong";
    private static final String PLAIN_PREV = "plain-prev";
    private static final String PLAIN_NEXT = "plain-next";
    private static final String PLAIN_SAME = "plain-same";
    private static final String SAMPLE_JWT = "sample-jwt";

    @Mock
    private JWTUtils jwtUtils;

    @Mock
    private UserMapper userMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private TokenRevocationService tokenRevocationService;

    @Mock
    private FileStorageService fileStorageService;

    @Mock
    private MinioProperties minioProperties;

    private UserServiceImpl userService;

    @BeforeAll
    static void initializeTableMetadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), User.class);
    }

    @BeforeEach
    void setUp() {
        userService = new UserServiceImpl(
                jwtUtils,
                userMapper,
                passwordEncoder,
                tokenRevocationService,
                fileStorageService,
                minioProperties
        );
    }

    @Test
    void loginShouldReturnTokenAndUserProfile() {
        User user = activeUser(HASH_STORED);
        user.setNickname("Alice");
        user.setEmail("alice@example.com");
        when(userMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches(PLAIN_INPUT, HASH_STORED)).thenReturn(true);
        when(jwtUtils.generateJWT(any())).thenReturn(SAMPLE_JWT);

        LoginResultVO result = userService.login("alice", PLAIN_INPUT);

        assertEquals(SAMPLE_JWT, result.getToken());
        assertEquals(USER_ID, result.getUser().getUserId());
        assertEquals("alice", result.getUser().getUsername());
        assertEquals("Alice", result.getUser().getNickname());
        assertEquals("alice@example.com", result.getUser().getEmail());
    }

    @Test
    void loginShouldRejectWrongPassword() {
        User user = activeUser(HASH_STORED);
        when(userMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches(PLAIN_WRONG, HASH_STORED)).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.login("alice", PLAIN_WRONG));

        assertTrue(ex.getMessage().contains("用户名或密码错误"));
        verify(jwtUtils, never()).generateJWT(any());
    }

    @Test
    void changePasswordShouldUpdateEncodedPasswordAndInvalidateSessions() {
        User user = activeUser(HASH_PREV);
        when(userMapper.selectById(USER_ID)).thenReturn(user);
        when(passwordEncoder.matches(PLAIN_PREV, HASH_PREV)).thenReturn(true);
        when(passwordEncoder.matches(PLAIN_NEXT, HASH_PREV)).thenReturn(false);
        when(passwordEncoder.encode(PLAIN_NEXT)).thenReturn(HASH_NEXT);
        when(userMapper.updateById(any(User.class))).thenReturn(1);

        userService.changePassword(LOGIN_USER, PLAIN_PREV, PLAIN_NEXT);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(userCaptor.capture());
        assertEquals(HASH_NEXT, userCaptor.getValue().getPassword());
        verify(passwordEncoder).encode(PLAIN_NEXT);
        verify(tokenRevocationService).invalidateAllForUser(USER_ID);
    }

    @Test
    void changePasswordShouldRejectWrongOldPassword() {
        User user = activeUser(HASH_PREV);
        when(userMapper.selectById(USER_ID)).thenReturn(user);
        when(passwordEncoder.matches(PLAIN_WRONG, HASH_PREV)).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.changePassword(LOGIN_USER, PLAIN_WRONG, PLAIN_NEXT));

        assertTrue(ex.getMessage().contains("旧密码"));
        verify(userMapper, never()).updateById(any(User.class));
        verify(passwordEncoder, never()).encode(anyString());
        verify(tokenRevocationService, never()).invalidateAllForUser(any());
    }

    @Test
    void changePasswordShouldRejectSamePassword() {
        User user = activeUser(HASH_PREV);
        when(userMapper.selectById(USER_ID)).thenReturn(user);
        when(passwordEncoder.matches(PLAIN_SAME, HASH_PREV)).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.changePassword(LOGIN_USER, PLAIN_SAME, PLAIN_SAME));

        assertTrue(ex.getMessage().contains("相同"));
        verify(userMapper, never()).updateById(any(User.class));
        verify(passwordEncoder, never()).encode(anyString());
        verify(tokenRevocationService, never()).invalidateAllForUser(any());
    }

    @Test
    void changePasswordShouldRejectDisabledUser() {
        User user = activeUser(HASH_PREV);
        user.setStatus(0);
        when(userMapper.selectById(USER_ID)).thenReturn(user);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.changePassword(LOGIN_USER, PLAIN_PREV, PLAIN_NEXT));

        assertTrue(ex.getMessage().contains("禁用"));
        verify(passwordEncoder, never()).matches(anyString(), anyString());
        verify(userMapper, never()).updateById(any(User.class));
        verify(tokenRevocationService, never()).invalidateAllForUser(any());
    }

    @Test
    void changePasswordShouldRollbackWhenSessionInvalidationFails() {
        User user = activeUser(HASH_PREV);
        when(userMapper.selectById(USER_ID)).thenReturn(user);
        when(passwordEncoder.matches(PLAIN_PREV, HASH_PREV)).thenReturn(true);
        when(passwordEncoder.matches(PLAIN_NEXT, HASH_PREV)).thenReturn(false);
        when(passwordEncoder.encode(PLAIN_NEXT)).thenReturn(HASH_NEXT);
        List<String> updatedHashes = new ArrayList<>();
        when(userMapper.updateById(any(User.class))).thenAnswer(invocation -> {
            User arg = invocation.getArgument(0);
            updatedHashes.add(arg.getPassword());
            return 1;
        });
        doThrow(new BusinessException("会话失效失败，请稍后重试"))
                .when(tokenRevocationService).invalidateAllForUser(USER_ID);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.changePassword(LOGIN_USER, PLAIN_PREV, PLAIN_NEXT));

        assertTrue(ex.getMessage().contains("修改密码失败"));
        assertEquals(List.of(HASH_NEXT, HASH_PREV), updatedHashes);
        assertEquals(HASH_PREV, user.getPassword());
    }

    @Test
    void changePasswordShouldWarnWhenRollbackAlsoFails() {
        User user = activeUser(HASH_PREV);
        when(userMapper.selectById(USER_ID)).thenReturn(user);
        when(passwordEncoder.matches(PLAIN_PREV, HASH_PREV)).thenReturn(true);
        when(passwordEncoder.matches(PLAIN_NEXT, HASH_PREV)).thenReturn(false);
        when(passwordEncoder.encode(PLAIN_NEXT)).thenReturn(HASH_NEXT);
        when(userMapper.updateById(any(User.class))).thenReturn(1, 0);
        doThrow(new BusinessException("会话失效失败，请稍后重试"))
                .when(tokenRevocationService).invalidateAllForUser(USER_ID);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.changePassword(LOGIN_USER, PLAIN_PREV, PLAIN_NEXT));

        assertTrue(ex.getMessage().contains("密码已修改但会话失效失败"));
        verify(userMapper, times(2)).updateById(any(User.class));
    }

    @Test
    void getProfileShouldReturnUserFieldsWithoutPassword() {
        User user = activeUser(HASH_PREV);
        user.setNickname("Alice");
        user.setEmail("alice@example.com");
        user.setAvatar("https://img/a.png");
        when(userMapper.selectById(USER_ID)).thenReturn(user);

        UserProfileVO profile = userService.getProfile(LOGIN_USER);

        assertEquals(USER_ID, profile.getUserId());
        assertEquals("alice", profile.getUsername());
        assertEquals("Alice", profile.getNickname());
        assertEquals("alice@example.com", profile.getEmail());
        assertEquals("https://img/a.png", profile.getAvatar());
        assertEquals(1, profile.getStatus());
    }

    @Test
    void updateProfileShouldUpdateNicknameAndEmail() {
        User user = activeUser(HASH_PREV);
        when(userMapper.selectById(USER_ID)).thenReturn(user);
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(userMapper.update(any(), any())).thenReturn(1);

        UpdateProfileDTO dto = new UpdateProfileDTO("  Bob  ", "bob@example.com");
        UserProfileVO profile = userService.updateProfile(LOGIN_USER, dto);

        verify(userMapper).update(any(), any());
        assertEquals("Bob", profile.getNickname());
        assertEquals("bob@example.com", profile.getEmail());
        assertEquals("Bob", user.getNickname());
        assertEquals("bob@example.com", user.getEmail());
    }

    @Test
    void updateProfileShouldRejectDuplicateEmail() {
        User user = activeUser(HASH_PREV);
        user.setEmail("old@example.com");
        when(userMapper.selectById(USER_ID)).thenReturn(user);
        when(userMapper.selectCount(any())).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.updateProfile(LOGIN_USER, new UpdateProfileDTO(null, "taken@example.com")));

        assertTrue(ex.getMessage().contains("邮箱已被占用"));
        verify(userMapper, never()).update(any(), any());
    }

    @Test
    void updateProfileShouldClearBlankEmail() {
        User user = activeUser(HASH_PREV);
        user.setEmail("old@example.com");
        when(userMapper.selectById(USER_ID)).thenReturn(user);
        when(userMapper.update(any(), any())).thenReturn(1);

        UserProfileVO profile = userService.updateProfile(LOGIN_USER, new UpdateProfileDTO(null, "   "));

        verify(userMapper).update(any(), any());
        assertNull(user.getEmail());
        assertNull(profile.getEmail());
        verify(userMapper, never()).selectCount(any());
    }

    @Test
    void updateAvatarShouldUploadToPublicBucketAndDeleteOldObject() {
        User user = activeUser(HASH_PREV);
        user.setAvatar(PUBLIC_ENDPOINT + "/" + PUBLIC_BUCKET + "/avatar/7/old.png");
        when(userMapper.selectById(USER_ID)).thenReturn(user);
        when(userMapper.updateById(any(User.class))).thenReturn(1);
        when(minioProperties.getPublicEndpoint()).thenReturn(PUBLIC_ENDPOINT);
        when(minioProperties.getBucketPublic()).thenReturn(PUBLIC_BUCKET);
        when(fileStorageService.getAccessUrl(eq(BucketType.PUBLIC), anyString(), isNull()))
                .thenAnswer(invocation -> PUBLIC_ENDPOINT + "/" + PUBLIC_BUCKET + "/" + invocation.getArgument(1));

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "avatar.png",
                "image/png",
                "img".getBytes(StandardCharsets.UTF_8)
        );

        UserProfileVO profile = userService.updateAvatar(LOGIN_USER, file);

        ArgumentCaptor<String> objectKeyCaptor = ArgumentCaptor.forClass(String.class);
        verify(fileStorageService).upload(eq(file), eq(BucketType.PUBLIC), objectKeyCaptor.capture());
        String newObjectKey = objectKeyCaptor.getValue();
        assertTrue(newObjectKey.startsWith("avatar/7/"));
        assertTrue(newObjectKey.endsWith(".png"));
        assertEquals(PUBLIC_ENDPOINT + "/" + PUBLIC_BUCKET + "/" + newObjectKey, profile.getAvatar());
        verify(fileStorageService).delete(BucketType.PUBLIC, "avatar/7/old.png");
    }

    @Test
    void updateAvatarShouldRejectInvalidContentType() {
        User user = activeUser(HASH_PREV);
        when(userMapper.selectById(USER_ID)).thenReturn(user);

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "avatar.txt",
                "text/plain",
                "img".getBytes(StandardCharsets.UTF_8)
        );

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.updateAvatar(LOGIN_USER, file));

        assertTrue(ex.getMessage().contains("头像仅支持"));
        verify(fileStorageService, never()).upload(any(), any(), any());
    }

    @Test
    void updateAvatarShouldCleanupObjectWhenDatabaseUpdateFails() {
        User user = activeUser(HASH_PREV);
        when(userMapper.selectById(USER_ID)).thenReturn(user);
        when(userMapper.updateById(any(User.class))).thenReturn(0);
        when(fileStorageService.getAccessUrl(eq(BucketType.PUBLIC), anyString(), isNull()))
                .thenAnswer(invocation -> PUBLIC_ENDPOINT + "/" + PUBLIC_BUCKET + "/" + invocation.getArgument(1));

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "avatar.png",
                "image/png",
                "img".getBytes(StandardCharsets.UTF_8)
        );

        assertThrows(BusinessException.class, () -> userService.updateAvatar(LOGIN_USER, file));

        ArgumentCaptor<String> objectKeyCaptor = ArgumentCaptor.forClass(String.class);
        verify(fileStorageService).upload(eq(file), eq(BucketType.PUBLIC), objectKeyCaptor.capture());
        verify(fileStorageService).delete(BucketType.PUBLIC, objectKeyCaptor.getValue());
    }

    private User activeUser(String storedHash) {
        User user = new User();
        user.setId(USER_ID);
        user.setUsername("alice");
        user.setPassword(storedHash);
        user.setStatus(1);
        return user;
    }
}
