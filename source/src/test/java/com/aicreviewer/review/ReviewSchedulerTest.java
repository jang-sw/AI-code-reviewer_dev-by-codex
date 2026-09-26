package com.aicreviewer.review;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReviewSchedulerTest {
    private static final Instant NOW = Instant.parse("2026-09-26T04:15:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void disabledScheduleDoesNotCreateNewWork() {
        var requests = mock(ReviewRequestRepository.class);
        new ReviewScheduler(requests, false, "0 0 * * * *", CLOCK).reviewApprovedProjects();
        verifyNoInteractions(requests);
    }

    @Test
    void startupBetweenTicksCatchesMissedDueProjectsAndCoalescesToNextUtcHour() {
        var requests = mock(ReviewRequestRepository.class);
        when(requests.scheduledCandidates(NOW, 1000)).thenReturn(List.of(10L, 20L, 30L));
        when(requests.enqueueScheduled(eq(20L), any(), any())).thenReturn(ReviewRequestRepository.EnqueueResult.ALREADY_QUEUED);
        new ReviewScheduler(requests, true, "0 0 * * * *", CLOCK).reviewApprovedProjects();
        for (long id : List.of(10L, 20L, 30L)) {
            verify(requests).enqueueScheduled(id, NOW, Instant.parse("2026-09-26T05:00:00Z"));
        }
    }

    @Test
    void oneProjectFailureLeavesLaterDueProjectsEligible() {
        var requests = mock(ReviewRequestRepository.class);
        when(requests.scheduledCandidates(NOW, 1000)).thenReturn(List.of(10L, 20L));
        when(requests.enqueueScheduled(eq(10L), any(), any())).thenThrow(new DataAccessResourceFailureException("synthetic"));
        new ReviewScheduler(requests, true, "0 0 * * * *", CLOCK).reviewApprovedProjects();
        verify(requests).enqueueScheduled(20L, NOW, Instant.parse("2026-09-26T05:00:00Z"));
    }

    @Test
    void databaseOutageDoesNotEscapeToDefaultSchedulerExceptionLogger() {
        var requests = mock(ReviewRequestRepository.class);
        when(requests.scheduledCandidates(any(), anyInt())).thenThrow(new DataAccessResourceFailureException("synthetic"));
        assertThatCode(() -> new ReviewScheduler(requests, true, "0 0 * * * *", CLOCK).reviewApprovedProjects()).doesNotThrowAnyException();
        verify(requests, never()).enqueueScheduled(anyLong(), any(), any());
    }

    @Test
    void customCronAndExactBoundaryAlwaysAdvanceToAFutureUtcOccurrence() {
        var requests = mock(ReviewRequestRepository.class);
        when(requests.scheduledCandidates(NOW, 1000)).thenReturn(List.of(10L));
        new ReviewScheduler(requests, true, "0 */15 * * * *", CLOCK).reviewApprovedProjects();
        verify(requests).enqueueScheduled(10L, NOW, Instant.parse("2026-09-26T04:30:00Z"));
    }

    @Test
    void invalidOrImpossibleCronFailsConfigurationValidation() {
        var requests = mock(ReviewRequestRepository.class);
        assertThatThrownBy(() -> new ReviewScheduler(requests, true, "bad cron", CLOCK)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReviewScheduler(requests, true, "0 0 0 31 2 *", CLOCK)).isInstanceOf(IllegalArgumentException.class);
    }
}
