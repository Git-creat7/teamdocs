package asia.creat.teamdocsbackend.filter;

import asia.creat.filter.JwtAuthenticationFilter;
import asia.creat.security.LoginUser;
import asia.creat.security.RestAuthenticationEntryPoint;
import asia.creat.utils.JWTUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {
    @Mock
    private JWTUtils jwtUtils;

    @Mock
    private Claims claims;

    @Mock
    private FilterChain filterChain;

    private ObjectMapper objectMapper;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        filter = new JwtAuthenticationFilter(
                jwtUtils,
                new RestAuthenticationEntryPoint(objectMapper)
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // Missing credentials continue through this filter and are rejected by Spring Security.
    @Test
    void shouldLeaveMissingTokenForSecurityToHandle() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/space/list");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void shouldAuthenticateValidBearerToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/space/list");
        request.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(jwtUtils.parseToken("valid-token")).thenReturn(claims);
        when(claims.get("userId", Long.class)).thenReturn(7L);
        when(claims.get("username", String.class)).thenReturn("alice");

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        LoginUser principal = assertInstanceOf(LoginUser.class, authentication.getPrincipal());
        assertEquals(7L, principal.getUserId());
        assertEquals("alice", principal.getUsername());
    }

    @Test
    void shouldReturnResultBodyWhenTokenIsInvalid() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/space/list");
        request.addHeader("Authorization", "Bearer invalid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(jwtUtils.parseToken("invalid-token")).thenThrow(new RuntimeException("expired"));

        filter.doFilter(request, response, filterChain);

        verify(filterChain, never()).doFilter(request, response);
        assertUnauthorizedResult(response);
    }

    @Test
    void shouldReturnResultBodyWhenAuthorizationHeaderIsMalformed() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/space/list");
        request.addHeader("Authorization", "Basic abc");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain, never()).doFilter(request, response);
        assertUnauthorizedResult(response);
    }

    private void assertUnauthorizedResult(MockHttpServletResponse response) throws Exception {
        assertEquals(401, response.getStatus());
        assertTrue(MediaType.APPLICATION_JSON.isCompatibleWith(
                MediaType.parseMediaType(response.getContentType())
        ));
        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertEquals(0, body.get("code").asInt());
        assertEquals("未登录或登录状态已失效", body.get("msg").asText());
    }
}
