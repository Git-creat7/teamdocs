package asia.creat.controller;

import asia.creat.common.Result;
import asia.creat.dto.UserLoginDTO;
import asia.creat.dto.UserRegisterDTO;
import asia.creat.security.LoginUser;
import asia.creat.service.RateLimitService;
import asia.creat.service.RecentDocumentService;
import asia.creat.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;


import static asia.creat.utils.RedisConstants.*;

@RestController
@RequestMapping("/user")
public class UserController {
    private final UserService userService;
    private final RateLimitService rateLimitService;
    private final RecentDocumentService recentDocumentService;

    public UserController(UserService userService, RateLimitService rateLimitService, RecentDocumentService recentDocumentService) {
        this.userService = userService;
        this.rateLimitService = rateLimitService;
        this.recentDocumentService = recentDocumentService;
    }

    @PostMapping("/register")
    public Result register(@RequestBody @Validated UserRegisterDTO dto){
        userService.register(dto.getUsername(),dto.getPassword());
        return Result.success();
    }
    @PostMapping("/login")
    public Result login(@RequestBody @Validated UserLoginDTO dto, HttpServletRequest request){
        String ip = request.getRemoteAddr();

        if (rateLimitService.isRateLimited(LOGIN_LIMIT_PREFIX +ip,LOGIN_LIMIT_WINDOW,MAX_LOGIN_ATTEMPTS)) {
            return Result.error("请求过于频繁");
        }
        String token = userService.login(dto.getUsername(),dto.getPassword());
        return Result.success(token);
    }
    //调试Token
    @GetMapping("/info")
    public Result info(@AuthenticationPrincipal LoginUser loginUser) {
        return Result.success(loginUser);
    }

    @GetMapping("/recent-documents")
    public Result recent(@AuthenticationPrincipal LoginUser loginUser) {
        return Result.success(recentDocumentService.getRecentDocuments(loginUser.getUserId()));
    }
}
