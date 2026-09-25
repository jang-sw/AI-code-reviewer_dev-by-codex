package com.aicreviewer.review;

import com.aicreviewer.ai.AiReviewClient;
import com.aicreviewer.ai.ReviewFinding;
import com.aicreviewer.ai.ReviewResult;
import com.aicreviewer.git.GitCommit;
import com.aicreviewer.git.GitRepositoryClient;
import com.aicreviewer.issue.IssueService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReviewAuthorAssignmentTest {
    private static final String EMAIL = "author@example.test";
    private ReviewTestDatabase db;
    private ReviewRepository repository;
    private GitRepositoryClient git;
    private ReviewCoordinator coordinator;

    @BeforeEach
    void setup() {
        db = new ReviewTestDatabase();
        repository = new ReviewRepository(db.jdbc);
        git = mock(GitRepositoryClient.class);
        var ai = mock(AiReviewClient.class);
        when(ai.review(any())).thenReturn(new ReviewResult("Summary", List.of(new ReviewFinding("HIGH", "Validation needed", "A.java", 1, "Description", "Suggestion"))));
        var locks = mock(ProjectReviewLock.class);
        when(locks.tryAcquire(10)).thenReturn(Optional.of(mock(ProjectReviewLock.Lease.class)));
        coordinator = new ReviewCoordinator(repository, locks, git, ai, db.transactionManager, 100);
    }

    @AfterEach
    void cleanup() { db.close(); }

    @Test
    void activeGitLabMappingUsesWholeNormalizedEmailAndPersistsReasonWithoutExposingEmail() {
        gitLab("https://gitlab.example.test/group/repo");
        mapping("https://gitlab.example.test", EMAIL, 2);

        review(new GitCommit("a".repeat(40), null, " AUTHOR@EXAMPLE.TEST ", "Commit", "diff"));

        assertAssignment(2, "GIT_EMAIL_MAPPING");
        assertThat(db.jdbc.queryForObject("select author_email from reviewed_commit", String.class)).isEqualTo(EMAIL);
        assertThat(db.jdbc.queryForList("select detail from audit_event", String.class)).allSatisfy(detail -> assertThat(detail).doesNotContain(EMAIL, "AUTHOR@EXAMPLE.TEST"));
        assertThat(db.jdbc.queryForObject("select detail from audit_event where action = 'ISSUES_ASSIGNED'", String.class)).contains("reason=GIT_EMAIL_MAPPING", "assignee=2", "mapping=");
        var issue = new IssueService(db.jdbc).list(repository.actor("author"), "", 0).issues().getFirst();
        assertThat(issue).doesNotContainKey("author_email").containsEntry("assignment_reason", "GIT_EMAIL_MAPPING");
        assertThat(repository.reviewedCommits(10).getFirst()).doesNotContainKey("author_email");
    }

    @Test
    void disabledMappingUserFallsBackToOwner() {
        gitLab("https://gitlab.example.test/group/repo");
        mapping("https://gitlab.example.test", EMAIL, 2);
        db.jdbc.update("update app_user set enabled = false where id = 2");

        review(commit(null, EMAIL));

        assertAssignment(1, "PROJECT_OWNER_FALLBACK");
        assertThat(db.jdbc.queryForObject("select count(*) from audit_event where action = 'ISSUE_ASSIGNEE_FALLBACK'", Long.class)).isEqualTo(1L);
    }

    @Test
    void mappingOnAnotherHostCannotAssignAnIssue() {
        gitLab("https://gitlab.example.test/group/repo");
        mapping("https://another.example.test", EMAIL, 2);

        review(commit(null, EMAIL));

        assertAssignment(1, "PROJECT_OWNER_FALLBACK");
    }

    @Test
    void sameHostnameDifferentPortAndSchemeRemainDifferentOrigins() {
        gitLab("https://gitlab.example.test:8443/group/repo");
        mapping("https://gitlab.example.test", EMAIL, 2);
        mapping("http://gitlab.example.test:8443", EMAIL, 4);

        review(commit(null, EMAIL));

        assertAssignment(1, "PROJECT_OWNER_FALLBACK");
    }

    @Test
    void explicitDefaultHttpsPortMatchesCanonicalOrigin() {
        gitLab("https://gitlab.example.test:443/group/repo");
        mapping("https://gitlab.example.test", EMAIL, 2);

        review(commit(null, EMAIL));

        assertAssignment(2, "GIT_EMAIL_MAPPING");
    }

    @Test
    void githubAccountAssociationTakesPrecedenceOverConflictingEmailMapping() {
        mapping("https://github.com", EMAIL, 4);

        review(commit("AUTHOR-GIT", EMAIL));

        assertAssignment(2, "GITHUB_ACCOUNT");
        assertThat(db.jdbc.queryForObject("select detail from audit_event where action = 'ISSUES_ASSIGNED'", String.class)).doesNotContain("mapping=");
    }

    @Test
    void githubWithoutMatchedAccountCanUseOriginEmailMapping() {
        mapping("https://github.com", EMAIL, 2);

        review(commit("not-registered", EMAIL));

        assertAssignment(2, "GIT_EMAIL_MAPPING");
    }

    @Test
    void gitLabUsernameNeverUsesGlobalGithubIdentityNamespace() {
        gitLab("https://gitlab.example.test/group/repo");

        review(commit("author-git", EMAIL));

        assertAssignment(1, "PROJECT_OWNER_FALLBACK");
    }

    @Test
    void mappingRequiresFullEmailNotOnlyLocalPart() {
        gitLab("https://gitlab.example.test/group/repo");
        mapping("https://gitlab.example.test", "author@other.example.test", 2);

        review(commit(null, EMAIL));

        assertAssignment(1, "PROJECT_OWNER_FALLBACK");
    }

    @Test
    void deletingMappingDoesNotRewriteExistingIssueAssignmentSnapshot() {
        gitLab("https://gitlab.example.test/group/repo");
        mapping("https://gitlab.example.test", EMAIL, 2);
        review(commit(null, EMAIL));
        db.jdbc.update("delete from git_author_mapping");
        assertAssignment(2, "GIT_EMAIL_MAPPING");
        assertThat(db.jdbc.queryForObject("select detail from audit_event where action = 'ISSUES_ASSIGNED'", String.class)).contains("mapping=");
    }

    private GitCommit commit(String login, String email) { return new GitCommit("a".repeat(40), login, email, "Commit", "diff"); }

    private void review(GitCommit commit) {
        when(git.commits(any(), any(), any(), anyInt())).thenReturn(List.of(commit));
        assertThat(coordinator.reviewProject(10, "owner")).isEqualTo(ReviewCoordinator.Outcome.SUCCEEDED);
    }

    private void gitLab(String url) {
        db.jdbc.update("update project set repository_url = ?, provider = 'GITLAB', repository_host = ?, repository_path = 'group/repo' where id = 10", url, URI.create(url).getHost());
    }

    private void mapping(String origin, String email, long userId) {
        db.jdbc.update("insert into git_author_mapping(user_id, repository_origin, author_email) values (?, ?, ?)", userId, origin, email);
    }

    private void assertAssignment(long userId, String reason) {
        assertThat(db.jdbc.queryForObject("select assignee_id from review_issue", Long.class)).isEqualTo(userId);
        assertThat(db.jdbc.queryForObject("select assignment_reason from review_issue", String.class)).isEqualTo(reason);
    }
}
