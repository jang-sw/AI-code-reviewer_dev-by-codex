package com.aicreviewer.review;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReviewDispatcherTest {
    @Test
    void acceptedRequestRemainsSuccessfulWhenImmediateDispatchDatabaseReadFails() {
        var coordinator = mock(ReviewCoordinator.class);
        var requests = mock(ReviewRequestRepository.class);
        when(requests.enqueueManual(eq(10L), eq("owner"), any())).thenReturn(ReviewRequestRepository.EnqueueResult.QUEUED);
        when(requests.candidates(any(), anyInt())).thenThrow(new DataAccessResourceFailureException("synthetic database unavailable"));
        var dispatcher = new ReviewDispatcher(coordinator, requests, 1);
        try {
            assertThat(dispatcher.submitManual(10, "owner")).isEqualTo(ReviewDispatcher.Submission.QUEUED);
            verifyNoInteractions(coordinator);
        } finally { dispatcher.close(); }
    }

    @Test
    void authorizationOrEnqueueFailureDoesNotDispatchOrAcknowledge() {
        var coordinator = mock(ReviewCoordinator.class);
        var requests = mock(ReviewRequestRepository.class);
        when(requests.enqueueManual(eq(10L), eq("other"), any())).thenThrow(new AccessDeniedException("Denied"));
        var dispatcher = new ReviewDispatcher(coordinator, requests, 1);
        try {
            assertThatThrownBy(() -> dispatcher.submitManual(10, "other")).isInstanceOf(AccessDeniedException.class);
            verify(requests, never()).candidates(any(), anyInt());
            verifyNoInteractions(coordinator);
        } finally { dispatcher.close(); }
    }

    @Test
    void closedExecutorKeepsAcceptedWorkInDatabaseForNextProcess() {
        var coordinator = mock(ReviewCoordinator.class);
        var requests = mock(ReviewRequestRepository.class);
        when(requests.enqueueManual(eq(10L), eq("owner"), any())).thenReturn(ReviewRequestRepository.EnqueueResult.QUEUED);
        when(requests.isActive(10)).thenReturn(true);
        var dispatcher = new ReviewDispatcher(coordinator, requests, 1);
        dispatcher.close();
        assertThat(dispatcher.submitManual(10, "owner")).isEqualTo(ReviewDispatcher.Submission.QUEUED);
        assertThat(dispatcher.isQueued(10)).isTrue();
        verify(requests, never()).candidates(any(), anyInt());
        verifyNoInteractions(coordinator);
    }

    @Test
    void limitsLocalWorkersAndLeavesExcessCandidatesInDatabase() throws Exception {
        var coordinator = mock(ReviewCoordinator.class);
        var requests = mock(ReviewRequestRepository.class);
        var first = request(1);
        var second = request(2);
        var third = request(3);
        when(requests.candidates(any(), eq(ReviewDispatcher.POLL_CANDIDATES))).thenReturn(List.of(first, second, third));
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        when(coordinator.processRequest(any())).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test timed out");
            return ReviewCoordinator.Outcome.SUCCEEDED;
        });
        var dispatcher = new ReviewDispatcher(coordinator, requests, 2);
        try {
            dispatcher.drain();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            dispatcher.drain();
            verify(requests, times(1)).candidates(any(), eq(ReviewDispatcher.POLL_CANDIDATES));
            verify(coordinator, never()).processRequest(third);
        } finally { release.countDown(); dispatcher.close(); }
        verify(coordinator, times(1)).processRequest(first);
        verify(coordinator, times(1)).processRequest(second);
        verifyNoMoreInteractions(coordinator);
    }

    @Test
    void forcedShutdownInterruptsRunningWorkWithoutDeletingDurableRequests() throws Exception {
        var coordinator = mock(ReviewCoordinator.class);
        var requests = mock(ReviewRequestRepository.class);
        var request = request(1);
        when(requests.candidates(any(), anyInt())).thenReturn(List.of(request));
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        when(coordinator.processRequest(request)).thenAnswer(invocation -> {
            entered.countDown();
            try { new CountDownLatch(1).await(5, TimeUnit.SECONDS); }
            catch (InterruptedException exception) { interrupted.countDown(); Thread.currentThread().interrupt(); }
            return ReviewCoordinator.Outcome.FAILED;
        });
        var dispatcher = new ReviewDispatcher(coordinator, requests, 1);
        try {
            dispatcher.drain();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            dispatcher.close(1, TimeUnit.MILLISECONDS);
            assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
            dispatcher.drain();
            verify(requests).candidates(any(), anyInt());
            verifyNoMoreInteractions(requests);
        } finally { dispatcher.close(); }
    }

    @Test
    void duplicateManualSubmissionStillUsesDurableAuthorizationAndDeduplication() {
        var coordinator = mock(ReviewCoordinator.class);
        var requests = mock(ReviewRequestRepository.class);
        when(requests.enqueueManual(eq(10L), eq("owner"), any())).thenReturn(ReviewRequestRepository.EnqueueResult.ALREADY_QUEUED);
        var dispatcher = new ReviewDispatcher(coordinator, requests, 1);
        try {
            assertThat(dispatcher.submitManual(10, "owner")).isEqualTo(ReviewDispatcher.Submission.ALREADY_QUEUED);
            verify(requests).enqueueManual(eq(10L), eq("owner"), any());
        } finally { dispatcher.close(); }
    }

    @Test
    void maintenancePauseAcceptsDurableRequestsWithoutRunningWorkers() {
        var coordinator = mock(ReviewCoordinator.class);
        var requests = mock(ReviewRequestRepository.class);
        when(requests.enqueueManual(eq(10L), eq("owner"), any())).thenReturn(ReviewRequestRepository.EnqueueResult.QUEUED);
        var dispatcher = new ReviewDispatcher(coordinator, requests, 1, null, false);
        try {
            assertThat(dispatcher.submitManual(10, "owner")).isEqualTo(ReviewDispatcher.Submission.QUEUED);
            dispatcher.drain();
            verify(requests, never()).candidates(any(), anyInt());
            verifyNoInteractions(coordinator);
        } finally { dispatcher.close(); }
    }

    @Test
    void failsStartupWhenLockAndTransactionConnectionsWouldExhaustPool() {
        var coordinator = mock(ReviewCoordinator.class);
        var requests = mock(ReviewRequestRepository.class);
        assertThatThrownBy(() -> new ReviewDispatcher(coordinator, requests, 5, 10)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2 * app.review.concurrency + 2");
        assertThatThrownBy(() -> new ReviewDispatcher(coordinator, requests, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReviewDispatcher(coordinator, requests, 17)).isInstanceOf(IllegalArgumentException.class);
        var sufficient = new ReviewDispatcher(coordinator, requests, 4, 10);
        sufficient.close();
    }

    private static ReviewRequestRepository.Request request(long projectId) {
        var request = mock(ReviewRequestRepository.Request.class);
        when(request.projectId()).thenReturn(projectId);
        return request;
    }
}
