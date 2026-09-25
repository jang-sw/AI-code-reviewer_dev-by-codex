package com.aicreviewer.review;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;

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
        assertThat(dispatcher.scheduledCandidateLimit()).isZero();
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

    @Test
    void candidateBudgetReachesPastAllInFlightProjectsToFillTheLastQueueSlot() throws Exception {
        var coordinator = mock(ReviewCoordinator.class);
        var entered = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        when(coordinator.reviewProject(1L, null)).thenAnswer(invocation -> {
            entered.countDown();
            if (!finish.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test timed out");
            return ReviewCoordinator.Outcome.SUCCEEDED;
        });
        var dispatcher = new ReviewDispatcher(coordinator, 1);
        try {
            assertThat(dispatcher.scheduledCandidateLimit()).isEqualTo(1001);
            assertThat(dispatcher.submitScheduled(1L)).isEqualTo(ReviewDispatcher.Submission.QUEUED);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            for (long id = 2; id <= 1000; id++) {
                assertThat(dispatcher.submitScheduled(id)).isEqualTo(ReviewDispatcher.Submission.QUEUED);
            }
            // Only one slot is free, but limiting SQL to one row would only see already-running ID 1.
            assertThat(dispatcher.scheduledCandidateLimit()).isEqualTo(1001);
            when(coordinator.scheduledProjects(1001)).thenReturn(LongStream.rangeClosed(1, 1001).boxed().toList());
            var scheduler = new ReviewScheduler(coordinator, dispatcher, true);
            scheduler.reviewApprovedProjects();
            assertThat(dispatcher.isQueued(1001L)).isTrue();
            assertThat(dispatcher.scheduledCandidateLimit()).isZero();
            scheduler.reviewApprovedProjects();
            verify(coordinator, times(1)).scheduledProjects(1001);
            assertThat(dispatcher.submitScheduled(1002L)).isEqualTo(ReviewDispatcher.Submission.CAPACITY_REACHED);
            assertThat(dispatcher.isQueued(1002L)).isFalse();
        } finally {
            finish.countDown();
            dispatcher.close();
        }
        verify(coordinator, times(1)).reviewProject(1001L, null);
    }

    @Test
    void maximumConfiguredConcurrencyHasABoundedCandidateBudget() {
        var dispatcher = new ReviewDispatcher(mock(ReviewCoordinator.class), 16);
        try {
            assertThat(dispatcher.scheduledCandidateLimit()).isEqualTo(1016);
        } finally { dispatcher.close(); }
    }
}
