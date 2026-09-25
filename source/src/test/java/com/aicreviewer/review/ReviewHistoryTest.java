package com.aicreviewer.review;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewHistoryTest {
    private ReviewTestDatabase db;
    private ReviewRepository repository;

    @BeforeEach
    void setup() {
        db = new ReviewTestDatabase();
        repository = new ReviewRepository(db.jdbc);
    }

    @AfterEach
    void cleanup() { db.close(); }

    @ParameterizedTest
    @ValueSource(ints = { 50, 51, 52 })
    void independentSlicesKeepEveryOlderRowWithoutExposingTheLookaheadRow(int count) {
        seed(count);
        var firstCommits = repository.reviewedCommits(10, 0);
        var firstRuns = repository.runs(10, 0);
        var secondCommits = repository.reviewedCommits(10, 1);
        var secondRuns = repository.runs(10, 1);

        assertThat(firstCommits.rows()).hasSize(50);
        assertThat(firstRuns.rows()).hasSize(50);
        assertThat(firstCommits.rows().getFirst().get("id")).isEqualTo((long) count);
        assertThat(firstRuns.rows().getFirst().get("id")).isEqualTo((long) count);
        assertThat(firstCommits.hasNext()).isEqualTo(count > 50);
        assertThat(firstRuns.hasNext()).isEqualTo(count > 50);
        assertThat(secondCommits.rows()).hasSize(count - 50);
        assertThat(secondRuns.rows()).hasSize(count - 50);
        assertThat(secondCommits.hasNext()).isFalse();
        assertThat(secondRuns.hasNext()).isFalse();
        assertThat(secondCommits.page()).isEqualTo(1);
        assertThat(secondRuns.page()).isEqualTo(1);
        assertThat(repository.reviewedCommits(10)).isEqualTo(firstCommits.rows());
        assertThat(repository.runs(10)).isEqualTo(firstRuns.rows());
        if (count > 50) {
            assertThat(secondCommits.rows().getLast().get("id")).isEqualTo(1L);
            assertThat(secondRuns.rows().getLast().get("id")).isEqualTo(1L);
            assertThat(secondCommits.rows()).noneMatch(firstCommits.rows()::contains);
            assertThat(secondRuns.rows()).noneMatch(firstRuns.rows()::contains);
        }
    }

    @Test
    void paginationKeepsProjectScopeAndIssueCountsAndDoesNotExposeAuthorEmail() {
        seed(52);
        db.jdbc.update("insert into project(id, name, repository_url, provider, repository_host, repository_path, owner_id, status) values (11, 'Other', 'https://github.com/org/other', 'GITHUB', 'github.com', 'org/other', 4, 'APPROVED')");
        db.jdbc.update("insert into review_run(id, project_id, status) values (1000, 11, 'SUCCEEDED')");
        db.jdbc.update("insert into reviewed_commit(id, project_id, commit_sha, summary) values (1000, 11, ?, 'Other project')", "f".repeat(40));
        db.jdbc.update("insert into review_issue(project_id, reviewed_commit_id, assignee_id, severity, title, file_path, description, suggestion) values (10, 1, 1, 'LOW', 'Finding', 'app.java', 'Description', 'Suggestion')");

        assertThat(repository.runs(10, 0).rows()).noneMatch(row -> row.get("id").equals(1000L));
        assertThat(repository.reviewedCommits(10, 0).rows()).noneMatch(row -> row.get("id").equals(1000L));
        assertThat(repository.reviewedCommits(10, 1).rows().getLast()).containsEntry("issue_count", 1L).doesNotContainKey("author_email");
        assertThat(repository.reviewedCommits(11, 0).rows()).hasSize(1);
        assertThat(repository.runs(11, 0).rows()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(ints = { -1, 10001, Integer.MAX_VALUE })
    void invalidPagesFailBeforeQueryingHistory(int page) {
        assertThatThrownBy(() -> repository.runs(10, page)).isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value()).isEqualTo(400));
        assertThatThrownBy(() -> repository.reviewedCommits(10, page)).isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value()).isEqualTo(400));
    }

    @Test
    void maximumAllowedAndEmptyPagesReturnAnEmptySliceWithoutANextLink() {
        for (int page : new int[] { 0, 1, 10000 }) {
            assertThat(repository.runs(10, page).rows()).isEmpty();
            assertThat(repository.runs(10, page).hasNext()).isFalse();
            assertThat(repository.reviewedCommits(10, page).rows()).isEmpty();
            assertThat(repository.reviewedCommits(10, page).hasNext()).isFalse();
        }
    }

    private void seed(int count) {
        for (int id = 1; id <= count; id++) {
            db.jdbc.update("insert into review_run(id, project_id, status, reviewed_commits) values (?, 10, 'SUCCEEDED', 1)", id);
            db.jdbc.update("insert into reviewed_commit(id, project_id, commit_sha, author_email, summary) values (?, 10, ?, 'private@example.test', ?)",
                    id, String.format("%040x", id), "Summary " + id);
        }
    }
}
