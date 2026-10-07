package asia.creat.teamdocsbackend.memory;

import asia.creat.common.exception.GlobalExceptionHandler;
import asia.creat.controller.UserMemoryController;
import asia.creat.memory.UserMemoryData.*;
import asia.creat.memory.UserMemoryService;
import asia.creat.security.LoginUser;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(OutputCaptureExtension.class)
class UserMemoryControllerTest {
    private final UserMemoryService service = mock(UserMemoryService.class);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginUser(7L, "alice"), null, List.of()));
        mvc = MockMvcBuilders.standaloneSetup(new UserMemoryController(service))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void allEndpointsUseTheAuthenticatedUserNotAnInjectedUserId() throws Exception {
        when(service.view(7)).thenReturn(new View(false, 0, List.of()));
        mvc.perform(get("/user/memory?userId=999")).andExpect(jsonPath("$.data.enabled").value(false));
        mvc.perform(put("/user/memory/settings?userId=999").contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true,\"version\":0}")).andExpect(jsonPath("$.code").value(1));
        mvc.perform(put("/user/memory/items/code_language?userId=999").contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"Java\",\"version\":1}")).andExpect(jsonPath("$.code").value(1));
        mvc.perform(delete("/user/memory/items/code_language?version=2&userId=999")).andExpect(jsonPath("$.code").value(1));
        mvc.perform(delete("/user/memory?version=3&userId=999")).andExpect(jsonPath("$.code").value(1));
        verify(service).view(7);
        verify(service).settings(7, new Settings(true, 0L));
        verify(service).edit(7, "code_language", new Edit("Java", 1L));
        verify(service).delete(7, "code_language", 2);
        verify(service).clear(7, 3);
        verifyNoMoreInteractions(service);
    }

    @Test
    void invalidPrivateContentAndMissingSettingsAreNotExecutedOrLogged(CapturedOutput output) throws Exception {
        String secret = "PRIVATE-MEMORY-MARKER";
        mvc.perform(put("/user/memory/items/occupation").contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"" + secret.repeat(20) + "\",\"version\":0}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("记忆参数无效，请检查内容长度和版本后重试"));
        mvc.perform(put("/user/memory/settings").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0}")).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.msg").value("记忆参数无效，请检查内容长度和版本后重试"));
        verifyNoInteractions(service);
        assertFalse(output.getAll().contains(secret));
    }
}
