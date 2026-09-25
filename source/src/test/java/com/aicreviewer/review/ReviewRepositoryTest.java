package com.aicreviewer.review;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
        assertThat(repository.approvedProjectIds()).containsExactly(11L, 10L);
    }
}
