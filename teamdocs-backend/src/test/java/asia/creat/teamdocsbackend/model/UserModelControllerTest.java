package asia.creat.teamdocsbackend.model;

import asia.creat.controller.UserModelController;
import asia.creat.common.exception.GlobalExceptionHandler;
import asia.creat.model.*;
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
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(OutputCaptureExtension.class)
class UserModelControllerTest {
    private final UserModelService models = mock(UserModelService.class);
    private final AiDependencyHealth health = mock(AiDependencyHealth.class);
    private MockMvc mvc;

    @BeforeEach void init() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginUser(7L, "alice"), null, List.of()));
        mvc = MockMvcBuilders.standaloneSetup(new UserModelController(models, health))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }
    @AfterEach void clean() { SecurityContextHolder.clearContext(); }

    @Test void cannotSelectAnotherUsersConfigurationOrTriggerProbesByReading() throws Exception {
        when(models.view(7)).thenReturn(new UserModelData.View(false, 0, "", "", false, true));
        mvc.perform(get("/user/ai/model?userId=8")).andExpect(jsonPath("$.data.enabled").value(false));
        mvc.perform(get("/user/ai/dependencies")).andExpect(status().isOk());
        verify(models).view(7);
        verify(health).status(7);
        verifyNoMoreInteractions(models, health);
    }

    @Test void saveAndTestUseAuthenticatedIdentityWithoutReturningKeys() throws Exception {
        String body = "{\"enabled\":true,\"version\":0,\"baseUrl\":\"https://api.example.com/v1\",\"modelName\":\"model\",\"apiKey\":\"private-test-key\"}";
        mvc.perform(put("/user/ai/model?userId=8").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(jsonPath("$.code").value(1));
        mvc.perform(post("/user/ai/model/test?userId=8").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(jsonPath("$.code").value(1));
        verify(models).save(eq(7L), any());
        verify(health).testDraft(eq(7L), any());
    }

    @Test void validationErrorsNeverLogOrEchoApiKeys(CapturedOutput output) throws Exception {
        String secret = "PRIVATE-MODEL-KEY-MARKER";
        var response = mvc.perform(put("/user/ai/model").contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true,\"version\":0,\"baseUrl\":\"\",\"modelName\":\"\",\"apiKey\":\"" + secret.repeat(300) + "\"}"))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        assertFalse(response.getResponse().getContentAsString().contains(secret));
        assertFalse(output.getAll().contains(secret));
        verifyNoInteractions(models, health);
    }
}
