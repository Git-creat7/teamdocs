package asia.creat.service.impl;

import asia.creat.common.exception.BusinessException;

import asia.creat.entity.User;
import asia.creat.mapper.UserMapper;
import asia.creat.service.UserService;
import asia.creat.utils.JWTUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class UserServiceImpl implements UserService {
    @Autowired
    private JWTUtils jwtUtils;
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private PasswordEncoder passwordEncoder;

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
        //生成token
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", user.getId());
        claims.put("username", user.getUsername());
        return jwtUtils.generateJWT(claims);
    }
}
