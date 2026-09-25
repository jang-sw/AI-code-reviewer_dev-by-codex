package com.aicreviewer.review;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewRepositoryTest {
    private ReviewTestDatabase db;
    private ReviewRepository repository;

    @BeforeEach void setup() { db = new ReviewTestDatabase(); repository = new ReviewRepository(db.jdbc); }
    @AfterEach void cleanup() { db.close(); }

    @Test
    void dashboardAndRecentProjectsRespectOwnerScope() {
        db.jdbc.update("insert into project(id, name, repository_url, provider, repository_host, repository_path, owner_id, status) values (11, 'Other', 'https://github.com/org/other', 'GITHUB', 'github.com', 'org/other', 4, 'PENDING')");
        db.jdbc.update("insert into review_run(project_id, status) values (10, 'FAILED')");
        assertThat(repository.dashboard(repository.actor("owner"))).containsEntry("projectCount", 1L).containsEntry("pendingCount", 0L).containsEntry("failedRunCount", 1L);
        assertThat(repository.dashboard(repository.actor("other"))).containsEntry("projectCount", 1L).containsEntry("pendingCount", 1L).containsEntry("failedRunCount", 0L);
        assertThat(repository.dashboard(repository.actor("admin"))).containsEntry("projectCount", 2L).containsEntry("pendingCount", 1L);
        assertThat(repository.recentProjects(repository.actor("owner"))).hasSize(1);
    }

    @Test
    void schedulerSelectsOnlyApprovedProjectsWithNeverReviewedProjectsFirst() {
        db.jdbc.update("insert into project(id, name, repository_url, provider, repository_host, repository_path, owner_id, status) values (11, 'New', 'https://github.com/org/new', 'GITHUB', 'github.com', 'org/new', 1, 'APPROVED'), (12, 'Pending', 'https://github.com/org/pending', 'GITHUB', 'github.com', 'org/pending', 1, 'PENDING')");
        db.jdbc.update("insert into review_run(project_id, status) values (10, 'SUCCEEDED')");
        assertThat(repository.approvedProjectIds(2)).containsExactly(11L, 10L);
        assertThat(repository.approvedProjectIds(1)).containsExactly(11L);
    }

    @Test
    void schedulerPreservesNeverRunThenOldestLatestAttemptOrderingWithinSqlLimit() {
        db.jdbc.update("insert into project(id, name, repository_url, provider, repository_host, repository_path, owner_id, status) values " +
                "(11, 'Never', 'https://github.com/org/never', 'GITHUB', 'github.com', 'org/never', 1, 'APPROVED'), " +
                "(12, 'Old', 'https://github.com/org/old', 'GITHUB', 'github.com', 'org/old', 1, 'APPROVED'), " +
                "(13, 'Newer', 'https://github.com/org/newer', 'GITHUB', 'github.com', 'org/newer', 1, 'APPROVED'), " +
                "(14, 'Never2', 'https://github.com/org/never2', 'GITHUB', 'github.com', 'org/never2', 1, 'APPROVED')");
        db.jdbc.update("insert into review_run(project_id, status, started_at) values " +
                "(10, 'SUCCEEDED', timestamp '2026-01-01 00:00:00'), " +
                "(10, 'FAILED', timestamp '2026-01-04 00:00:00'), " +
                "(12, 'FAILED', timestamp '2026-01-02 00:00:00'), " +
                "(13, 'SUCCEEDED', timestamp '2026-01-03 00:00:00')");
        assertThat(repository.approvedProjectIds(3)).containsExactly(11L, 14L, 12L);
        assertThat(repository.approvedProjectIds(5)).containsExactly(11L, 14L, 12L, 13L, 10L);
        assertThatThrownBy(() -> repository.approvedProjectIds(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.approvedProjectIds(ReviewDispatcher.MAX_SCHEDULE_CANDIDATES + 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
