package com.aicreviewer.review;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReviewDispatcherTest {
    @Test
    void manualSubmissionRunsInBackgroundAndDeduplicatesConcurrentRequest() throws Exception {
        var coordinator = mock(ReviewCoordinator.class);
        var entered = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        when(coordinator.reviewProject(10, "owner")).thenAnswer(invocation -> {
            entered.countDown();
            if (!finish.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test timed out");
            return ReviewCoordinator.Outcome.SUCCEEDED;
        });
        var dispatcher = new ReviewDispatcher(coordinator, 1);
        try {
            assertThat(dispatcher.submitManual(10, "owner")).isEqualTo(ReviewDispatcher.Submission.QUEUED);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(dispatcher.isQueued(10)).isTrue();
            assertThat(dispatcher.submitManual(10, "owner")).isEqualTo(ReviewDispatcher.Submission.ALREADY_QUEUED);
        } finally {
            finish.countDown();
            dispatcher.close();
        }
        verify(coordinator, times(1)).reviewProject(10, "owner");
        assertThat(dispatcher.isQueued(10)).isFalse();
    }

    @Test
    void authorizationFailureDoesNotEnqueueAnything() {
        var coordinator = mock(ReviewCoordinator.class);
        doThrow(new AccessDeniedException("Denied")).when(coordinator).authorizeManual(10, "other");
        var dispatcher = new ReviewDispatcher(coordinator, 1);
        try {
            assertThatThrownBy(() -> dispatcher.submitManual(10, "other")).isInstanceOf(AccessDeniedException.class);
            assertThat(dispatcher.isQueued(10)).isFalse();
            verify(coordinator, never()).reviewProject(anyLong(), any());
        } finally { dispatcher.close(); }
    }

    @Test
    void closedExecutorRejectsSubmissionAndClearsLocalReservation() {
        var coordinator = mock(ReviewCoordinator.class);
        var dispatcher = new ReviewDispatcher(coordinator, 1);
        dispatcher.close();
        assertThat(dispatcher.submitManual(10, "owner")).isEqualTo(ReviewDispatcher.Submission.CAPACITY_REACHED);
        assertThat(dispatcher.isQueued(10)).isFalse();
        verify(coordinator, never()).reviewProject(anyLong(), any());
    }

    @Test
    void failsStartupWhenLockAndTransactionConnectionsWouldExhaustPool() {
        var coordinator = mock(ReviewCoordinator.class);
        assertThatThrownBy(() -> new ReviewDispatcher(coordinator, 5, 10)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2 * app.review.concurrency + 2");
        var sufficient = new ReviewDispatcher(coordinator, 4, 10);
        sufficient.close();
    }
}
