package com.aicreviewer.review;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

@Component
public class ReviewScheduler {
    static final int CANDIDATE_LIMIT = 1000;
    private static final System.Logger LOG = System.getLogger(ReviewScheduler.class.getName());
    private final ReviewRequestRepository requests;
    private final boolean enabled;
    private final CronExpression cron;
    private final Clock clock;

    @Autowired
    public ReviewScheduler(ReviewRequestRepository requests, @Value("${app.review.enabled:true}") boolean enabled,
                           @Value("${app.review.cron:0 0 * * * *}") String cron) {
        this(requests, enabled, cron, Clock.systemUTC());
    }

    ReviewScheduler(ReviewRequestRepository requests, boolean enabled, String expression, Clock clock) {
        this.requests = requests;
        this.enabled = enabled;
        this.cron = CronExpression.parse(expression);
        this.clock = clock;
        nextAfter(clock.instant());
    }

    /** A persisted due time catches missed ticks without replaying every missed hour. */
    @Scheduled(fixedDelay = 30000, initialDelay = 1000)
    public void reviewApprovedProjects() {
        if (!enabled) return;
        Instant now = clock.instant();
        Instant next = nextAfter(now);
        try {
            for (long projectId : requests.scheduledCandidates(now, CANDIDATE_LIMIT)) {
                try {
                    requests.enqueueScheduled(projectId, now, next);
                } catch (RuntimeException exception) {
                    // Leave this project's due time intact for a later reconciliation.
                    LOG.log(System.Logger.Level.WARNING, "Project {0} review scheduling failed: {1}",
                            projectId, exception.getClass().getSimpleName());
                }
            }
        } catch (RuntimeException exception) {
            LOG.log(System.Logger.Level.WARNING, "Review schedule reconciliation failed: {0}", exception.getClass().getSimpleName());
        }
    }

    private Instant nextAfter(Instant now) {
        var next = cron.next(now.atZone(ZoneOffset.UTC));
        if (next == null) throw new IllegalArgumentException("Review schedule must have a future occurrence");
        return next.toInstant();
    }
}
