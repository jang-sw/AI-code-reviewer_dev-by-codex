package com.aicreviewer.review;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.*;

class ReviewSchedulerTest {
    @Test
    void disabledScheduleDoesNotQueryOrSubmitWork() {
        var coordinator = mock(ReviewCoordinator.class);
        var dispatcher = mock(ReviewDispatcher.class);
        new ReviewScheduler(coordinator, dispatcher, false).reviewApprovedProjects();
        verifyNoInteractions(coordinator, dispatcher);
    }

    @Test
    void submitsApprovedProjectsUntilQueueCapacityIsReached() {
        var coordinator = mock(ReviewCoordinator.class);
        var dispatcher = mock(ReviewDispatcher.class);
        when(coordinator.scheduledProjects()).thenReturn(List.of(10L, 20L, 30L));
        when(dispatcher.submitScheduled(10L)).thenReturn(ReviewDispatcher.Submission.QUEUED);
        when(dispatcher.submitScheduled(20L)).thenReturn(ReviewDispatcher.Submission.CAPACITY_REACHED);

        new ReviewScheduler(coordinator, dispatcher, true).reviewApprovedProjects();

        verify(dispatcher).submitScheduled(10);
        verify(dispatcher).submitScheduled(20);
        verify(dispatcher, never()).submitScheduled(30);
    }
}
