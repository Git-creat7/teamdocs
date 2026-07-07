package asia.creat.controller;

import asia.creat.common.Result;
import asia.creat.dto.UserLoginDTO;
import asia.creat.dto.UserRegisterDTO;
import asia.creat.security.LoginUser;
import asia.creat.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user")
public class UserController {
    @Autowired
    private UserService userService;

    @PostMapping("/register")
    public Result register(@RequestBody @Validated UserRegisterDTO dto){
        userService.register(dto.getUsername(),dto.getPassword());
        return Result.success();
    }
    @PostMapping("/login")
    public Result login(@RequestBody @Validated UserLoginDTO dto){
        String token = userService.login(dto.getUsername(),dto.getPassword());
        return Result.success(token);
    }
    //调试Token
    @GetMapping("/info")
    public Result info(@AuthenticationPrincipal LoginUser loginUser) {
        return Result.success(loginUser);
    }
}
