package com.aicreviewer.review;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.view.InternalResourceView;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReviewControllerTest {
    private final ReviewRepository repository = mock(ReviewRepository.class);
    private final ReviewDispatcher dispatcher = mock(ReviewDispatcher.class);
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
        mvc = MockMvcBuilders.standaloneSetup(new ReviewController(repository, dispatcher))
                .setSingleView(new InternalResourceView("/WEB-INF/jsp/reviews.jsp")).build();
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
    }
}
