package asia.creat.service.impl;

import asia.creat.common.BucketType;
import asia.creat.common.exception.BusinessException;
import asia.creat.config.MinioProperties;
import asia.creat.dto.UpdateProfileDTO;
import asia.creat.entity.User;
import asia.creat.mapper.UserMapper;
import asia.creat.security.LoginUser;
import asia.creat.service.FileStorageService;
import asia.creat.service.TokenRevocationService;
import asia.creat.service.UserService;
import asia.creat.utils.JWTUtils;
import asia.creat.vo.LoginResultVO;
import asia.creat.vo.UserProfileVO;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class UserServiceImpl implements UserService {
    private static final long MAX_AVATAR_SIZE = 2 * 1024 * 1024;
    private static final Set<String> ALLOWED_AVATAR_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/gif",
            "image/webp"
    );

    private final JWTUtils jwtUtils;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final TokenRevocationService tokenRevocationService;
    private final FileStorageService fileStorageService;
    private final MinioProperties minioProperties;

    public UserServiceImpl(JWTUtils jwtUtils, UserMapper userMapper, PasswordEncoder passwordEncoder,
                           TokenRevocationService tokenRevocationService,
                           FileStorageService fileStorageService,
                           MinioProperties minioProperties) {
        this.jwtUtils = jwtUtils;
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.tokenRevocationService = tokenRevocationService;
        this.fileStorageService = fileStorageService;
        this.minioProperties = minioProperties;
    }

    @Override
    public void register(String username, String password) {
        LambdaQueryWrapper<User> lqw = new LambdaQueryWrapper<>();
        lqw.eq(User::getUsername, username);
        Long existUser = userMapper.selectCount(lqw);
        if (existUser > 0) {
            throw new BusinessException("用户名已存在");
        }
        User user = new User();
        user.setUsername(username);
        //加密
        user.setPassword(passwordEncoder.encode(password));
        userMapper.insert(user);
    }


    @Override
    public LoginResultVO login(String username, String password) {
        LambdaQueryWrapper<User> lqw = new LambdaQueryWrapper<>();
        lqw.eq(User::getUsername, username);
        User user = userMapper.selectOne(lqw);
        if(user == null || !passwordEncoder.matches(password, user.getPassword())){
            throw new BusinessException("用户名或密码错误");
        }

        if(user.getStatus() != null && user.getStatus() != 1){
            throw new BusinessException("用户已被禁用");
        }

        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", user.getId());
        claims.put("username", user.getUsername());
        String token = jwtUtils.generateJWT(claims);
        return new LoginResultVO(token, toProfileVO(user));
    }

    @Override
    public void changePassword(LoginUser loginUser, String oldPassword, String newPassword) {
        User user = userMapper.selectById(loginUser.getUserId());
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        if (user.getStatus() != null && user.getStatus() != 1) {
            throw new BusinessException("用户已被禁用");
        }
        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw new BusinessException("旧密码不正确");
        }
        if (Objects.equals(oldPassword, newPassword)
                || passwordEncoder.matches(newPassword, user.getPassword())) {
            throw new BusinessException("新密码不能与旧密码相同");
        }

        String oldPasswordHash = user.getPassword();
        String newPasswordHash = passwordEncoder.encode(newPassword);
        user.setPassword(newPasswordHash);
        int updated = userMapper.updateById(user);
        if (updated != 1) {
            throw new BusinessException("修改密码失败");
        }

        try {
            // 作废该账号全部已签发 JWT（含当前请求 token 与其它端会话）
            tokenRevocationService.invalidateAllForUser(loginUser.getUserId());
        } catch (RuntimeException e) {
            // Redis 会话作废失败时回滚密码，避免「库已改、接口失败、旧会话仍可用」
            user.setPassword(oldPasswordHash);
            int restored = userMapper.updateById(user);
            if (restored != 1) {
                throw new BusinessException("密码已修改但会话失效失败，请使用新密码重新登录", e);
            }
            throw new BusinessException("修改密码失败，请稍后重试", e);
        }
    }

    @Override
    public UserProfileVO getProfile(LoginUser loginUser) {
        return toProfileVO(requireActiveUser(loginUser.getUserId()));
    }

    @Override
    public UserProfileVO updateProfile(LoginUser loginUser, UpdateProfileDTO dto) {
        User user = requireActiveUser(loginUser.getUserId());

        boolean hasUpdate = false;
        LambdaUpdateWrapper<User> update = new LambdaUpdateWrapper<>();
        update.eq(User::getId, user.getId());

        if (dto.getNickname() != null) {
            String nickname = StrUtil.trim(dto.getNickname());
            if (StrUtil.isBlank(nickname)) {
                nickname = null;
            }
            update.set(User::getNickname, nickname);
            user.setNickname(nickname);
            hasUpdate = true;
        }

        if (dto.getEmail() != null) {
            String email = StrUtil.trim(dto.getEmail());
            if (StrUtil.isBlank(email)) {
                email = null;
            }
            if (StrUtil.isNotBlank(email) && !email.equalsIgnoreCase(StrUtil.nullToEmpty(user.getEmail()))) {
                LambdaQueryWrapper<User> emailQuery = new LambdaQueryWrapper<>();
                emailQuery.eq(User::getEmail, email)
                        .ne(User::getId, user.getId());
                if (userMapper.selectCount(emailQuery) > 0) {
                    throw new BusinessException("邮箱已被占用");
                }
            }
            update.set(User::getEmail, email);
            user.setEmail(email);
            hasUpdate = true;
        }

        if (!hasUpdate) {
            return toProfileVO(user);
        }

        int updated = userMapper.update(null, update);
        if (updated != 1) {
            throw new BusinessException("更新资料失败");
        }
        return toProfileVO(user);
    }

    @Override
    public UserProfileVO updateAvatar(LoginUser loginUser, MultipartFile file) {
        User user = requireActiveUser(loginUser.getUserId());
        validateAvatarFile(file);

        String originalName = file.getOriginalFilename();
        String ext = "";
        if (StrUtil.isNotBlank(originalName) && originalName.contains(".")) {
            ext = originalName.substring(originalName.lastIndexOf('.')).toLowerCase(Locale.ROOT);
        } else {
            ext = extensionFromContentType(file.getContentType());
        }

        String objectKey = String.format("avatar/%d/%s%s", user.getId(), UUID.randomUUID(), ext);
        fileStorageService.upload(file, BucketType.PUBLIC, objectKey);

        String avatarUrl = fileStorageService.getAccessUrl(BucketType.PUBLIC, objectKey, null);
        String oldAvatar = user.getAvatar();
        user.setAvatar(avatarUrl);
        try {
            int updated = userMapper.updateById(user);
            if (updated != 1) {
                throw new BusinessException("更新头像失败");
            }
        } catch (RuntimeException e) {
            try {
                fileStorageService.delete(BucketType.PUBLIC, objectKey);
            } catch (RuntimeException cleanupException) {
                log.error("头像信息保存失败，清理 MinIO 对象失败: objectKey={}", objectKey, cleanupException);
                e.addSuppressed(cleanupException);
            }
            throw e;
        }

        deleteOldAvatarIfPresent(oldAvatar, objectKey);
        return toProfileVO(user);
    }

    private void validateAvatarFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("头像文件不能为空");
        }
        if (file.getSize() > MAX_AVATAR_SIZE) {
            throw new BusinessException("头像大小不能超过 2MB");
        }
        String contentType = StrUtil.nullToEmpty(file.getContentType()).toLowerCase(Locale.ROOT);
        if (!ALLOWED_AVATAR_TYPES.contains(contentType)) {
            throw new BusinessException("头像仅支持 JPG、PNG、GIF、WEBP");
        }
    }

    private String extensionFromContentType(String contentType) {
        if (contentType == null) {
            return "";
        }
        return switch (contentType.toLowerCase(Locale.ROOT)) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            default -> "";
        };
    }

    private void deleteOldAvatarIfPresent(String oldAvatar, String newObjectKey) {
        String oldObjectKey = extractPublicObjectKey(oldAvatar);
        if (StrUtil.isBlank(oldObjectKey) || oldObjectKey.equals(newObjectKey)) {
            return;
        }
        try {
            fileStorageService.delete(BucketType.PUBLIC, oldObjectKey);
        } catch (RuntimeException e) {
            log.warn("删除旧头像失败: objectKey={}, error={}", oldObjectKey, e.getMessage());
        }
    }

    private String extractPublicObjectKey(String avatarUrl) {
        if (StrUtil.isBlank(avatarUrl)) {
            return null;
        }
        String publicEndpoint = StrUtil.removeSuffix(StrUtil.nullToEmpty(minioProperties.getPublicEndpoint()), "/");
        String bucket = minioProperties.getBucketPublic();
        if (StrUtil.isBlank(publicEndpoint) || StrUtil.isBlank(bucket)) {
            return null;
        }
        String prefix = publicEndpoint + "/" + bucket + "/";
        if (!avatarUrl.startsWith(prefix)) {
            return null;
        }
        String objectKey = avatarUrl.substring(prefix.length());
        return StrUtil.isBlank(objectKey) ? null : objectKey;
    }

    private User requireActiveUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        if (user.getStatus() != null && user.getStatus() != 1) {
            throw new BusinessException("用户已被禁用");
        }
        return user;
    }

    private UserProfileVO toProfileVO(User user) {
        UserProfileVO vo = new UserProfileVO();
        vo.setUserId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setEmail(user.getEmail());
        vo.setAvatar(user.getAvatar());
        vo.setStatus(user.getStatus());
        vo.setCreatedAt(user.getCreatedAt());
        return vo;
    }
}
