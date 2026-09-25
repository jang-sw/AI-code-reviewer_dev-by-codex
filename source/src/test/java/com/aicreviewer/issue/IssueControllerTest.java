package com.aicreviewer.issue;

import com.aicreviewer.review.ReviewActor;
import com.aicreviewer.review.ReviewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.view.InternalResourceView;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
        when(service.list(eq(actor), anyString(), eq(0))).thenReturn(new IssueService.IssuePage(List.of(), 0, 0));
        mvc = MockMvcBuilders.standaloneSetup(new IssueController(service, repository))
                .setSingleView(new InternalResourceView("/WEB-INF/jsp/issues.jsp")).build();
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
}
