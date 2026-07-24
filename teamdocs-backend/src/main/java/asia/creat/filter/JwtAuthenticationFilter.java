package asia.creat.filter;

import asia.creat.security.LoginUser;
import asia.creat.utils.JWTUtils;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

@Slf4j
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    @Autowired
    private JWTUtils jwtUtils;

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String requestURI = request.getServletPath();
        return "/user/login".equals(requestURI)
                || "/user/register".equals(requestURI)
                || "/actuator/health".equals(requestURI);
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String requestURI = request.getRequestURI();

        // 从Authorization请求头提取JWT token（格式：Bearer <token>）
        String authHeader = request.getHeader("Authorization");
        String token = (authHeader != null && authHeader.startsWith("Bearer "))
                ? authHeader.substring(7) : null;

        // token为空或无效时返回401未授权
        if(token == null || token.isEmpty()) {
            log.error("token缺失或格式错误");
            response.setStatus(401);
            return;
        }

        try{
            // 验证token签名并解析载荷，提取用户ID和用户名
            Claims claims = jwtUtils.parseToken(token);
            Long userId = claims.get("userId", Long.class);
            String username = claims.get("username", String.class);

            // 创建LoginUser对象，用于后续权限判断
            LoginUser loginUser = new LoginUser(userId, username);

            // 将认证信息放入SecurityContext，Spring Security后续会使用此信息
            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(loginUser,null, Collections.emptyList());
            SecurityContextHolder.getContext().setAuthentication(auth);
        } catch (Exception e) {
            // token验证失败（过期、签名错误等）返回401
            log.error("Token验证失败: {}", e.getMessage());
            response.setStatus(401);
            return;
        }

        // 请求通过验证，继续传递给下一个过滤器或目标接口
        log.info("用户 {} 访问了 {}", SecurityContextHolder.getContext().getAuthentication().getName(), requestURI);
        filterChain.doFilter(request,response);
    }
}
