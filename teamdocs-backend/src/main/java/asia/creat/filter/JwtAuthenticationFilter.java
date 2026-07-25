package asia.creat.filter;

import asia.creat.security.LoginUser;
import asia.creat.security.RestAuthenticationEntryPoint;
import asia.creat.service.TokenRevocationService;
import asia.creat.utils.JWTUtils;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

@Slf4j
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JWTUtils jwtUtils;
    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final TokenRevocationService tokenRevocationService;

    public JwtAuthenticationFilter(JWTUtils jwtUtils,
                                   RestAuthenticationEntryPoint authenticationEntryPoint,
                                   TokenRevocationService tokenRevocationService) {
        this.jwtUtils = jwtUtils;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.tokenRevocationService = tokenRevocationService;
    }

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
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authHeader == null) {
            filterChain.doFilter(request, response);
            return;
        }

        if (!authHeader.startsWith("Bearer ") || authHeader.substring(7).isBlank()) {
            authenticationEntryPoint.commence(
                    request,
                    response,
                    new BadCredentialsException("Invalid Authorization header")
            );
            return;
        }

        String token = authHeader.substring(7);

        try{
            // 验证token签名并解析载荷，提取用户ID和用户名
            Claims claims = jwtUtils.parseToken(token);
            String tokenId = claims.getId();
            if (tokenId == null || tokenId.isBlank()) {
                throw new IllegalArgumentException("JWT ID is missing");
            }
            if (tokenRevocationService.isRevoked(tokenId)) {
                throw new BadCredentialsException("JWT has been revoked");
            }
            Long userId = claims.get("userId", Long.class);
            String username = claims.get("username", String.class);
            if (userId == null || username == null) {
                throw new IllegalArgumentException("JWT claims are incomplete");
            }

            // 创建LoginUser对象，用于后续权限判断
            LoginUser loginUser = new LoginUser(userId, username);

            // 将认证信息放入SecurityContext，Spring Security后续会使用此信息
            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(loginUser,null, Collections.emptyList());
            SecurityContextHolder.getContext().setAuthentication(auth);
        } catch (Exception e) {
            // token验证失败（过期、签名错误等）返回401
            SecurityContextHolder.clearContext();
            log.warn("Token验证失败: {}", e.getMessage());
            authenticationEntryPoint.commence(
                    request,
                    response,
                    new BadCredentialsException("Invalid JWT", e)
            );
            return;
        }

        // 请求通过验证，继续传递给下一个过滤器或目标接口
        log.info("用户 {} 访问了 {}", SecurityContextHolder.getContext().getAuthentication().getName(), requestURI);
        filterChain.doFilter(request,response);
    }
}
