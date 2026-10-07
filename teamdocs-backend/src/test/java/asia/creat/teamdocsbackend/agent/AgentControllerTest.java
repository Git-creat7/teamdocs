package asia.creat.teamdocsbackend.agent;

import asia.creat.agent.AgentData.NewRun;
import asia.creat.agent.AgentEventHub;
import asia.creat.agent.AgentService;
import asia.creat.common.exception.GlobalExceptionHandler;
import asia.creat.controller.AgentController;
import asia.creat.security.LoginUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(OutputCaptureExtension.class)
class AgentControllerTest {
    private final AgentService service = mock(AgentService.class);
    private final LoginUser user = new LoginUser(7L, "alice");
    private MockMvc mvc;

    @BeforeEach
    void prepare() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
        mvc = MockMvcBuilders.standaloneSetup(new AgentController(service, mock(AgentEventHub.class)))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void submitUsesPathScopeAndAuthenticatedPrincipal() throws Exception {
        when(service.submit(eq(1L), eq(2L), any(NewRun.class), same(user))).thenReturn(3L);
        mvc.perform(post("/spaces/1/agent/sessions/2/runs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientRequestId\":\"client-key\",\"question\":\"如何备份\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1)).andExpect(jsonPath("$.data.runId").value(3));
        verify(service).submit(eq(1L), eq(2L), any(NewRun.class), same(user));
    }

    @Test
    void deletionUsesPathScopeAndAuthenticatedPrincipal() throws Exception {
        mvc.perform(delete("/spaces/1/agent/sessions/2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1));

        verify(service).deleteSession(1L, 2L, user);
    }

    @Test
    void invalidPrivateQuestionIsNeitherExecutedNorLogged(CapturedOutput output) throws Exception {
        String secret = "PRIVATE-QUESTION-MARKER";
        String payload = new ObjectMapper().writeValueAsString(Map.of(
                "clientRequestId", "valid-key", "question", secret.repeat(100)));

        mvc.perform(post("/spaces/1/agent/sessions/2/runs").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        mvc.perform(post("/spaces/1/agent/sessions/2/runs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientRequestId\":\"bad key\",\"question\":\"" + secret + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        verifyNoInteractions(service);

        assertFalse(output.getAll().contains(secret));
    }
}
