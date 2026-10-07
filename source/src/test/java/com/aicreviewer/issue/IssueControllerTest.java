package com.aicreviewer.issue;

import com.aicreviewer.review.ReviewActor;
import com.aicreviewer.review.ReviewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.view.InternalResourceViewResolver;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class IssueControllerTest {
    private final ReviewActor actor = new ReviewActor(1, "owner", false);
    private final IssueService service = mock(IssueService.class);
    private final ReviewRepository repository = mock(ReviewRepository.class);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        when(repository.actor("owner")).thenReturn(actor);
        when(service.list(eq(actor), anyString(), eq(0))).thenReturn(new IssueService.IssuePage(List.of(), 0, false));
        mvc = MockMvcBuilders.standaloneSetup(new IssueController(service, repository))
                .setViewResolvers(new InternalResourceViewResolver("/WEB-INF/jsp/", ".jsp")).build();
    }

    @Test
    void missingFilterDefaultsToOpen() throws Exception {
        mvc.perform(get("/issues").principal(() -> "owner"))
                .andExpect(status().isOk()).andExpect(model().attribute("filterStatus", "OPEN"));
        verify(service).list(actor, "OPEN", 0);
    }

    @Test
    void explicitEmptyFilterPreservesAllStatusesSelection() throws Exception {
        mvc.perform(get("/issues").param("status", "").principal(() -> "owner"))
                .andExpect(status().isOk()).andExpect(model().attribute("filterStatus", ""));
        verify(service).list(actor, "", 0);
    }

    @Test
    void statusChangePreservesListFilterAndPage() throws Exception {
        mvc.perform(post("/issues/42/status").principal(() -> "owner")
                        .param("status", "OPEN").param("filterStatus", "RESOLVED").param("page", "2"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/issues?status=RESOLVED&page=2"));
        verify(service).changeStatus(42, "OPEN", actor, "");
    }

    @Test
    void invalidReturnFilterIsRejectedBeforeMutation() throws Exception {
        mvc.perform(post("/issues/42/status").principal(() -> "owner")
                        .param("status", "OPEN").param("filterStatus", "https://example.test"))
                .andExpect(status().isBadRequest());
        verify(service, never()).changeStatus(anyLong(), anyString(), any());
        verify(service, never()).changeStatus(anyLong(), anyString(), any(), anyString());
    }

    @Test
    void allStatusSelectionSurvivesDetailAndMutation() throws Exception {
        when(service.detail(42, actor)).thenReturn(java.util.Map.of("title", "Finding"));
        mvc.perform(get("/issues/42").principal(() -> "owner").param("filterStatus", "").param("page", "1"))
                .andExpect(status().isOk()).andExpect(model().attribute("filterStatus", ""));
        mvc.perform(post("/issues/42/status").principal(() -> "owner").param("status", "RESOLVED")
                        .param("filterStatus", "").param("page", "1"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/issues?status=&page=1"));
    }

    @Test
    void manualReasonIsPassedOnlyToTheServiceWithoutEchoingItInTheRedirectOrFlash() throws Exception {
        String reason = "처리 사유 <script>fixture</script>";
        var result = mvc.perform(post("/issues/42/status").principal(() -> "owner")
                        .param("status", "RESOLVED").param("filterStatus", "").param("page", "2").param("reason", reason))
                .andExpect(redirectedUrl("/issues?status=&page=2")).andReturn();
        verify(service).changeStatus(42, "RESOLVED", actor, reason);
        org.assertj.core.api.Assertions.assertThat(result.getFlashMap().toString()).doesNotContain(reason, "fixture", "<script>");
    }

    @Test
    void manualDetailUsesTheHumanReviewPageTitle() throws Exception {
        when(service.detail(42, actor)).thenReturn(java.util.Map.of("issue_kind", "MANUAL_REVIEW", "title", "Manual task"));
        mvc.perform(get("/issues/42").principal(() -> "owner"))
                .andExpect(status().isOk()).andExpect(model().attribute("pageTitle", "수동 확인 업무"));
        verify(service).detail(42, actor);
    }

    @ParameterizedTest @ValueSource(strings = {"  four  ", "완료   ", "<a>", "😀😀😀"})
    void invalidManualReasonReturnsAnEditableAuthorizedDetailWithoutUsingFlashOrQueryParameters(String reason) throws Exception {
        var originalIssue = Map.<String, Object>of("id", 42L, "issue_kind", "MANUAL_REVIEW", "status", "OPEN");
        when(service.detail(42, actor)).thenReturn(originalIssue);
        doThrow(new ManualIssueReasonException()).when(service).changeStatus(42, "RESOLVED", actor, reason);

        var result = mvc.perform(post("/issues/42/status").principal(() -> "owner")
                        .param("status", "RESOLVED").param("filterStatus", "").param("page", "2").param("reason", reason))
                .andExpect(status().isBadRequest()).andExpect(model().attribute("issue", originalIssue))
                .andExpect(model().attribute("reasonError", ManualIssueReasonException.SAFE_MESSAGE))
                .andExpect(model().attribute("reasonFormValue", reason)).andExpect(model().attribute("reasonFormCleared", false))
                .andExpect(model().attribute("requestedIssueStatus", "RESOLVED"))
                .andExpect(model().attribute("filterStatus", "")).andExpect(model().attribute("page", 2)).andReturn();

        assertThat(result.getModelAndView().getViewName()).isEqualTo("issue-detail");
        assertThat(result.getResponse().getRedirectedUrl()).isNull();
        assertThat(result.getFlashMap().isEmpty()).isTrue();
        assertThat(originalIssue).containsEntry("status", "OPEN");
        var order = inOrder(repository, service);
        order.verify(repository).actor("owner");
        order.verify(service).changeStatus(42, "RESOLVED", actor, reason);
        order.verify(repository).actor("owner");
        order.verify(service).detail(42, actor);
    }

    @ParameterizedTest @MethodSource("unsafeRestorationValues")
    void unsafeReasonIsNeverPutInTheErrorModelOrFlash(String reason) throws Exception {
        when(service.detail(42, actor)).thenReturn(Map.of("id", 42L, "issue_kind", "MANUAL_REVIEW", "status", "OPEN"));
        doThrow(new ManualIssueReasonException()).when(service).changeStatus(42, "OPEN", actor, reason);

        var result = mvc.perform(post("/issues/42/status").principal(() -> "owner")
                        .param("status", "OPEN").param("reason", reason))
                .andExpect(status().isBadRequest()).andExpect(model().attribute("reasonFormValue", ""))
                .andExpect(model().attribute("reasonFormCleared", true)).andReturn();

        assertThat(result.getModelAndView().getModel().toString()).doesNotContain(reason);
        assertThat(result.getFlashMap().isEmpty()).isTrue();
        assertThat(result.getResponse().getRedirectedUrl()).isNull();
    }

    static Stream<String> unsafeRestorationValues() {
        return Stream.of("x".repeat(1001), "private\ninput", "private\u0000input", "password=synthetic-value",
                "토큰: synthetic-value", "Authorization: Bearer synthetic-value", "sk-" + "synthetic-fixture",
                "github_" + "pat_synthetic_fixture", "glpat-" + "synthetic-fixture",
                "-----BEGIN " + "PRIVATE KEY-----", "https://synthetic-user:synthetic-value@example.test/repo");
    }

    @Test
    void losingAccessDuringErrorRecoveryNeverReturnsTheIssueOrSubmittedReason() throws Exception {
        doThrow(new ManualIssueReasonException()).when(service).changeStatus(42, "OPEN", actor, "four");
        when(service.detail(42, actor)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));

        var result = mvc.perform(post("/issues/42/status").principal(() -> "owner")
                        .param("status", "OPEN").param("reason", "four"))
                .andExpect(status().isNotFound()).andReturn();

        assertThat(result.getModelAndView()).isNull();
        assertThat(result.getFlashMap().isEmpty()).isTrue();
    }

    @Test
    void unrelatedFailuresAreNotTreatedAsCorrectableReasonErrors() {
        doThrow(new IllegalArgumentException("unrelated failure")).when(service).changeStatus(42, "OPEN", actor, "four");

        assertThatThrownBy(() -> mvc.perform(post("/issues/42/status").principal(() -> "owner")
                .param("status", "OPEN").param("reason", "four"))).hasRootCauseInstanceOf(IllegalArgumentException.class);
        verify(service, never()).detail(anyLong(), any());
    }
}
