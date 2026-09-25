package com.aicreviewer.review;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.view.InternalResourceViewResolver;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ReviewRecoveryControllerTest {
    private static final String URL = "https://private-fixture@example.invalid/repo";
    private static final String REASON = "private reason fixture <script>bad</script>";
    private final ReviewRecoveryService service = mock(ReviewRecoveryService.class);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new ReviewRecoveryController(service))
                .setViewResolvers(new InternalResourceViewResolver("/WEB-INF/jsp/", ".jsp")).build();
    }

    @ParameterizedTest @EnumSource(ReviewRecoveryException.Failure.class)
    void expectedFailuresKeepHttpStatusAndRenderOnlyOwnedMessages(ReviewRecoveryException.Failure failure) throws Exception {
        var exception = new ReviewRecoveryException(failure);
        doThrow(exception).when(service).resetProgress(anyString(), anyLong(), anyString(), anyString(), anyString());
        var result = mvc.perform(post("/admin/projects/42/review-progress/reset").principal(() -> "admin")
                        .param("repositoryUrl", URL).param("reason", REASON).param("expectedCursor", "a".repeat(40))
                        .param("returnUrl", "https://example.invalid/collect"))
                .andExpect(status().is(exception.getStatusCode().value()))
                .andExpect(view().name("review-recovery-error"))
                .andExpect(model().attribute("projectId", 42L))
                .andExpect(model().attribute("recoveryErrorMessage", exception.safeMessage()))
                .andReturn();
        assertThat(result.getModelAndView().getModel()).containsOnlyKeys("projectId", "pageTitle", "recoveryErrorMessage");
        assertThat(result.getModelAndView().getModel().toString()).doesNotContain(URL, REASON, "returnUrl", "collect", "a".repeat(40));
        assertThat(result.getFlashMap().isEmpty()).isTrue();
        assertThat(result.getResponse().getRedirectedUrl()).isNull();
    }

    @ParameterizedTest @ValueSource(ints = {400, 401, 403, 404, 409, 500})
    void unrelatedExceptionsNeverEnterTheOwnedMessageView(int statusCode) throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.valueOf(statusCode), REASON)).when(service)
                .resetProgress(anyString(), anyLong(), anyString(), anyString(), anyString());
        var result = mvc.perform(post("/admin/projects/42/review-progress/reset").principal(() -> "admin")
                        .param("repositoryUrl", URL).param("reason", REASON).param("expectedCursor", "a".repeat(40)))
                .andExpect(status().is(statusCode)).andReturn();
        assertThat(result.getResolvedException()).isInstanceOf(ResponseStatusException.class);
        assertThat(result.getModelAndView()).isNull();
        assertThat(result.getFlashMap().isEmpty()).isTrue();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(REASON, URL);
    }

    @Test
    void successRetainsTheFixedProjectRedirectWithoutRetainingSubmittedValues() throws Exception {
        var result = mvc.perform(post("/admin/projects/42/review-progress/reset").principal(() -> "admin")
                        .param("repositoryUrl", URL).param("reason", REASON).param("expectedCursor", "a".repeat(40))
                        .param("returnUrl", "https://example.invalid/collect"))
                .andExpect(redirectedUrl("/projects/42")).andReturn();
        assertThat(result.getFlashMap().toString()).doesNotContain(URL, REASON, "collect");
        verify(service).resetProgress("admin", 42, URL, "a".repeat(40), REASON);
    }
}
