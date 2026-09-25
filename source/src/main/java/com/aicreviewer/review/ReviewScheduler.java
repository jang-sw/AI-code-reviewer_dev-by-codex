package com.aicreviewer.review;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReviewScheduler {
    private static final System.Logger LOG = System.getLogger(ReviewScheduler.class.getName());
    private final ReviewCoordinator coordinator;
    private final ReviewDispatcher dispatcher;
    private final boolean enabled;

    public ReviewScheduler(ReviewCoordinator coordinator, ReviewDispatcher dispatcher, @Value("${app.review.enabled:true}") boolean enabled) {
        this.coordinator = coordinator;
        this.dispatcher = dispatcher;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${app.review.cron:0 0 * * * *}", zone = "UTC")
    public void reviewApprovedProjects() {
        if (!enabled) return;
        int candidateLimit = dispatcher.scheduledCandidateLimit();
        if (candidateLimit == 0) return;
        for (long projectId : coordinator.scheduledProjects(candidateLimit)) {
            if (dispatcher.submitScheduled(projectId) == ReviewDispatcher.Submission.CAPACITY_REACHED) {
                LOG.log(System.Logger.Level.WARNING, "Review queue capacity reached; remaining projects will be considered at the next scheduled run");
                break;
            }
        }
    }
}
