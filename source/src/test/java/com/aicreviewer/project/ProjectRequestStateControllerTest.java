package com.aicreviewer.project;

import com.aicreviewer.identity.UserAccount;
import com.aicreviewer.identity.UserAccountService;
import com.aicreviewer.review.ReviewRequestRepository;
import com.aicreviewer.web.ReviewRequestView;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProjectRequestStateControllerTest {
    private final ProjectService projects = mock(ProjectService.class);
    private final UserAccountService users = mock(UserAccountService.class);
    private final ReviewRequestRepository requests = mock(ReviewRequestRepository.class);
    private final ProjectController controller = new ProjectController(projects, users, requests);

    @Test void deniedProjectDoesNotReadDurableRequest() {
        when(projects.getVisible("outsider", 10)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> controller.detail(() -> "outsider", 10, new ExtendedModelMap()))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(requests);
    }

    @Test void visibleProjectReceivesSafeRequestAfterItsPermissionCheck() {
        var now = Instant.now();
        when(users.requireAccount("owner")).thenReturn(new UserAccount(1, "owner", "owner", "USER", true, now));
        when(requests.find(10)).thenReturn(Optional.of(new ReviewRequestRepository.Request(10, "not-for-ui", "QUEUED", "MANUAL", 1L,
                now, now, null, 0, null, null, null)));
        var model = new ExtendedModelMap();
        assertThat(controller.detail(() -> "owner", 10, model)).isEqualTo("projects/detail");
        assertThat((ReviewRequestView) model.get("reviewRequest")).satisfies(view -> {
            assertThat(view.active()).isTrue();
            assertThat(view.state()).isEqualTo("QUEUED");
            assertThat(view.toString()).doesNotContain("not-for-ui", "requestedBy");
        });
        var order = inOrder(projects, requests);
        order.verify(projects).getVisible("owner", 10);
        order.verify(requests).find(10);
    }
}
