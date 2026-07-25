package asia.creat.service.impl;

import asia.creat.common.exception.BusinessException;
import asia.creat.entity.User;
import asia.creat.mapper.UserMapper;
import asia.creat.security.LoginUser;
import asia.creat.service.TokenRevocationService;
import asia.creat.service.UserService;
import asia.creat.utils.JWTUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@Service
public class UserServiceImpl implements UserService {
    private final JWTUtils jwtUtils;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final TokenRevocationService tokenRevocationService;

    public UserServiceImpl(JWTUtils jwtUtils, UserMapper userMapper, PasswordEncoder passwordEncoder,
                           TokenRevocationService tokenRevocationService) {
        this.jwtUtils = jwtUtils;
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.tokenRevocationService = tokenRevocationService;
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
    public String login(String username, String password) {
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
        return jwtUtils.generateJWT(claims);
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
}
