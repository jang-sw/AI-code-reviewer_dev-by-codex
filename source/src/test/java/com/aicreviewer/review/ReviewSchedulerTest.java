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
        when(dispatcher.scheduledCandidateLimit()).thenReturn(1002);
        when(coordinator.scheduledProjects(1002)).thenReturn(List.of(10L, 20L, 30L));
        when(dispatcher.submitScheduled(10L)).thenReturn(ReviewDispatcher.Submission.QUEUED);
        when(dispatcher.submitScheduled(20L)).thenReturn(ReviewDispatcher.Submission.CAPACITY_REACHED);

        new ReviewScheduler(coordinator, dispatcher, true).reviewApprovedProjects();

        verify(dispatcher).submitScheduled(10);
        verify(dispatcher).submitScheduled(20);
        verify(dispatcher, never()).submitScheduled(30);
    }

    @Test
    void saturatedDispatcherDoesNotLoadCandidates() {
        var coordinator = mock(ReviewCoordinator.class);
        var dispatcher = mock(ReviewDispatcher.class);
        when(dispatcher.scheduledCandidateLimit()).thenReturn(0);
        new ReviewScheduler(coordinator, dispatcher, true).reviewApprovedProjects();
        verifyNoInteractions(coordinator);
        verify(dispatcher, never()).submitScheduled(anyLong());
    }

    @Test
    void alreadyQueuedCandidatesDoNotPreventLaterProjectsFromUsingAvailableSlots() {
        var coordinator = mock(ReviewCoordinator.class);
        var dispatcher = mock(ReviewDispatcher.class);
        when(dispatcher.scheduledCandidateLimit()).thenReturn(1002);
        when(coordinator.scheduledProjects(1002)).thenReturn(List.of(10L, 20L, 30L));
        when(dispatcher.submitScheduled(10L)).thenReturn(ReviewDispatcher.Submission.ALREADY_QUEUED);
        when(dispatcher.submitScheduled(20L)).thenReturn(ReviewDispatcher.Submission.ALREADY_QUEUED);
        when(dispatcher.submitScheduled(30L)).thenReturn(ReviewDispatcher.Submission.QUEUED);
        new ReviewScheduler(coordinator, dispatcher, true).reviewApprovedProjects();
        verify(coordinator).scheduledProjects(1002);
        verify(dispatcher).submitScheduled(30L);
    }
}
