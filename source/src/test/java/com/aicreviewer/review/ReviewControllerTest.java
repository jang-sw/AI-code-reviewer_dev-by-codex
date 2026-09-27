package com.aicreviewer.review;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.view.InternalResourceView;
import org.springframework.web.servlet.view.InternalResourceViewResolver;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.time.Instant;
import com.aicreviewer.web.ReviewRequestView;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReviewControllerTest {
    private final ReviewRepository repository = mock(ReviewRepository.class);
    private final ReviewDispatcher dispatcher = mock(ReviewDispatcher.class);
    private final ReviewRequestRepository requests = mock(ReviewRequestRepository.class);
    private final ReviewActor actor = new ReviewActor(1, "owner", false);
    private final ReviewProject project = new ReviewProject(10, "Sample", "https://github.com/org/repo", "GITHUB",
            "github.com", "org/repo", 1, "APPROVED", null, null);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        when(repository.actor("owner")).thenReturn(actor);
        when(repository.authorizedProject(10, actor)).thenReturn(project);
        when(repository.runs(eq(10L), anyInt())).thenAnswer(call -> new ReviewRepository.HistoryPage(List.of(), call.getArgument(1), false));
        when(repository.reviewedCommits(eq(10L), anyInt())).thenAnswer(call -> new ReviewRepository.HistoryPage(List.of(), call.getArgument(1), false));
        when(requests.find(10)).thenReturn(Optional.empty());
        var views = new InternalResourceViewResolver();
        views.setPrefix("/WEB-INF/jsp/");
        views.setSuffix(".jsp");
        mvc = MockMvcBuilders.standaloneSetup(new ReviewController(repository, dispatcher, requests))
                .setViewResolvers(views).build();
    }

    @Test
    void omittedPagesKeepBothHistoriesOnTheFirstPage() throws Exception {
        mvc.perform(get("/reviews").param("projectId", "10").principal(() -> "owner"))
                .andExpect(status().isOk()).andExpect(model().attribute("commitPage", 0)).andExpect(model().attribute("runPage", 0));
        verify(repository).runs(10, 0);
        verify(repository).reviewedCommits(10, 0);
    }

    @Test
    void eachPageIsIndependentAndCommitLinksComeFromAuthorizedRepositoryData() throws Exception {
        String sha = "a".repeat(40);
        when(repository.reviewedCommits(10, 2)).thenReturn(new ReviewRepository.HistoryPage(List.of(Map.of("commit_sha", sha)), 2, true));

        mvc.perform(get("/reviews").param("projectId", "10").param("commitPage", "2").param("runPage", "3").principal(() -> "owner"))
                .andExpect(status().isOk()).andExpect(model().attribute("commitPage", 2)).andExpect(model().attribute("runPage", 3))
                .andExpect(model().attribute("hasNextCommitPage", true)).andExpect(model().attribute("hasNextRunPage", false))
                .andExpect(model().attribute("commits", List.of(Map.of("commit_sha", sha, "commit_url", "https://github.com/org/repo/commit/" + sha))));
        verify(repository).runs(10, 3);
        verify(repository).reviewedCommits(10, 2);
    }

    @Test
    void deniedProjectNeverLoadsEitherHistoryOrQueueStatus() {
        when(repository.authorizedProject(10, actor)).thenThrow(new AccessDeniedException("Denied"));

        assertThatThrownBy(() -> mvc.perform(get("/reviews").param("projectId", "10").param("commitPage", "4").principal(() -> "owner")))
                .hasRootCauseInstanceOf(AccessDeniedException.class);
        verify(repository, never()).runs(anyLong(), anyInt());
        verify(repository, never()).reviewedCommits(anyLong(), anyInt());
        verifyNoInteractions(dispatcher);
        verifyNoInteractions(requests);
    }

    @ParameterizedTest @ValueSource(strings = {"QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED"})
    void rendersSafeDurableRequestSnapshotAfterProjectAuthorization(String state) throws Exception {
        var now = Instant.now();
        when(requests.find(10)).thenReturn(Optional.of(new ReviewRequestRepository.Request(10, "private-request-id", state, "MANUAL", 987654321L,
                now.minusSeconds(120), now, now, 2, 20L, null, "PROJECT_INELIGIBLE")));
        var result = mvc.perform(get("/reviews").param("projectId", "10").principal(() -> "owner"))
                .andExpect(status().isOk()).andReturn();
        var view = (ReviewRequestView) result.getModelAndView().getModel().get("reviewRequest");
        assertThat(view.state()).isEqualTo(state);
        assertThat(view.toString()).doesNotContain("private-request-id", "987654321", "claim_token");
        var order = inOrder(repository, requests);
        order.verify(repository).actor("owner");
        order.verify(repository).authorizedProject(10, actor);
        order.verify(requests).find(10);
        verifyNoInteractions(dispatcher);
    }

    @Test
    void acceptedOrDuplicateRequestRedirectsToStoredStatusWithoutClaimingImmediateExecution() throws Exception {
        for (var submission : new ReviewDispatcher.Submission[] {ReviewDispatcher.Submission.QUEUED, ReviewDispatcher.Submission.ALREADY_QUEUED}) {
            when(dispatcher.submitManual(10, "owner")).thenReturn(submission);
            var result = mvc.perform(post("/projects/10/review").principal(() -> "owner"))
                    .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/reviews?projectId=10")).andReturn();
            String message = (String) result.getFlashMap().get("message");
            assertThat(message).doesNotContain("가득", "완료했습니다");
            assertThat(message).contains(submission == ReviewDispatcher.Submission.QUEUED ? "요청을 저장" : "이미 리뷰");
        }
    }

    @Test
    void authorizedCurrentRequestProgressKeepsIndependentHistoryPagesAndExcludesOwnershipData() throws Exception {
        var now = Instant.parse("2026-09-27T07:00:00Z");
        var request = new ReviewRequestRepository.Request(10, "private-progress-request", "RUNNING", "MANUAL", 987654321L,
                now, now, now, 2, 20L, null, null);
        var progress = new ReviewProgress("REVIEWING", now, 3, now);
        when(requests.find(10)).thenReturn(Optional.of(request));
        when(requests.progress(request)).thenReturn(Optional.of(progress));

        var result = mvc.perform(get("/reviews").param("projectId", "10").param("commitPage", "2").param("runPage", "3").principal(() -> "owner"))
                .andExpect(status().isOk()).andExpect(model().attribute("reviewProgress", progress))
                .andExpect(model().attribute("commitPage", 2)).andExpect(model().attribute("runPage", 3)).andReturn();
        assertThat(result.getModelAndView().getModel().get("reviewProgress").toString())
                .doesNotContain("private-progress-request", "987654321", "claimToken", "repository", "password");
        var order = inOrder(repository, requests);
        order.verify(repository).authorizedProject(10, actor);
        order.verify(requests).find(10);
        order.verify(requests).progress(request);
    }
}
