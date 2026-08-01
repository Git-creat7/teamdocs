package asia.creat.filter;

import asia.creat.security.LoginUser;
import asia.creat.security.RestAuthenticationEntryPoint;
import asia.creat.service.TokenRevocationService;
import asia.creat.utils.JWTUtils;
import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.Collections;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JWTUtils jwtUtils;
    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final TokenRevocationService tokenRevocationService;

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

        try {
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
            if (tokenRevocationService.isUserSessionInvalid(userId, claims.getIssuedAt())) {
                throw new BadCredentialsException("JWT has been invalidated");
            }

            LoginUser loginUser = new LoginUser(userId, username);

            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(loginUser, null, Collections.emptyList());
            SecurityContextHolder.getContext().setAuthentication(auth);
        } catch (Exception e) {
            SecurityContextHolder.clearContext();
            log.warn("Token验证失败: {}", e.getMessage());
            authenticationEntryPoint.commence(
                    request,
                    response,
                    new BadCredentialsException("Invalid JWT", e)
            );
            return;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String userInfo = "未认证用户";

        if (auth != null && auth.getPrincipal() instanceof LoginUser loginUser) {
            userInfo = String.format("%s(id: %s)", loginUser.getUsername(), loginUser.getUserId());
        }

        log.info("用户 {} 访问了 {}", userInfo, requestURI);
        filterChain.doFilter(request, response);
    }
}
