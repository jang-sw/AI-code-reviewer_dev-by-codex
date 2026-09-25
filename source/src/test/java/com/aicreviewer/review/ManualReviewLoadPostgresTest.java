package com.aicreviewer.review;

import com.aicreviewer.ai.AiReviewClient;
import com.aicreviewer.git.GitCommit;
import com.aicreviewer.git.GitRepositoryClient;
import com.aicreviewer.git.GitReviewBatch;
import com.aicreviewer.git.ManualReviewFile;
import com.aicreviewer.issue.IssueService;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Opt-in local PostgreSQL storage smoke at the supported per-commit manual-file limit.
 * Git evidence is synthetic and AI is never called; elapsed times are observations,
 * not a production throughput claim. Existing test data is never deleted or reset.
 */
@EnabledIfEnvironmentVariable(named = "RUN_REVIEW_LOAD_SMOKE", matches = "true")
@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = "jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/reviewer_integration")
class ManualReviewLoadPostgresTest {
    private static final int FILE_COUNT = 1000;
    private static final String COMMIT_SHA = "a".repeat(40);
    private static final String OBJECT_SHA = "c".repeat(40);

    @Test
    void persistsMaximumManualBatchPagesAllIssuesAndRetriesWithoutDuplicates() {
        var source = new DriverManagerDataSource();
        source.setUrl(System.getenv("TEST_DATABASE_URL"));
        source.setUsername(System.getenv().getOrDefault("TEST_DATABASE_USERNAME", "reviewer_test"));
        source.setPassword(System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", ""));
        var connectionSettings = new Properties();
        connectionSettings.setProperty("connectTimeout", "10");
        connectionSettings.setProperty("socketTimeout", "60");
        source.setConnectionProperties(connectionSettings);
        Flyway.configure().dataSource(source).load().migrate();
        var jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(30);
        var repository = new ReviewRepository(jdbc);
        var issues = new IssueService(jdbc);
        var git = mock(GitRepositoryClient.class);
        var ai = mock(AiReviewClient.class);
        var coordinator = new ReviewCoordinator(repository, new PostgresProjectReviewLock(source), git, ai,
                new DataSourceTransactionManager(source), 100);

        String suffix = UUID.randomUUID().toString().replace("-", "");
        String ownerName = "loadowner" + suffix;
        String adminName = "loadadmin" + suffix;
        String outsiderName = "loadother" + suffix;
        long ownerId = account(jdbc, ownerName, "USER");
        account(jdbc, adminName, "ADMIN");
        account(jdbc, outsiderName, "USER");
        String repositoryPath = "synthetic/manual-load-" + suffix;
        long projectId = jdbc.queryForObject("""
                insert into project(name,repository_url,provider,repository_host,repository_path,owner_id,status)
                values(?,?,'GITHUB','github.com',?,?,'APPROVED') returning id
                """, Long.class, "Synthetic manual load " + suffix, "https://github.com/" + repositoryPath,
                repositoryPath, ownerId);
        ReviewActor owner = repository.actor(ownerName);
        ReviewActor admin = repository.actor(adminName);
        ReviewActor outsider = repository.actor(outsiderName);
        long unrelatedIssueCount = jdbc.queryForObject("select count(*) from review_issue where project_id <> ?", Long.class, projectId);

        assertThat(ManualReviewFile.MAX_FILES).isEqualTo(FILE_COUNT);
        List<ManualReviewFile> files = IntStream.range(0, FILE_COUNT)
                .mapToObj(index -> new ManualReviewFile(String.format(Locale.ROOT, "manual/File%04d.dat", index),
                        null, OBJECT_SHA, null, "100644", "GIT_DIFF_BUDGET"))
                .toList();
        var commit = new GitCommit(COMMIT_SHA, ownerName, null, "Synthetic maximum manual batch", "", "MANUAL_ONLY",
                "Synthetic pinned-tree fixture: storage and pagination smoke only", files);
        var identicalBatch = new GitReviewBatch(List.of(commit), COMMIT_SHA);
        when(git.batch(any(), any(), any(), anySet(), anyInt())).thenAnswer(invocation -> {
            Set<String> persisted = invocation.getArgument(3);
            String cursor = invocation.getArgument(2);
            if (cursor == null) assertThat(persisted).isEmpty();
            else {
                assertThat(cursor).isEqualTo(COMMIT_SHA);
                assertThat(persisted).containsExactly(COMMIT_SHA);
            }
            // Deliberately replay the exact same batch, exercising durable deduplication.
            return identicalBatch;
        });

        assertThatThrownBy(() -> coordinator.reviewProject(projectId, outsiderName)).isInstanceOf(AccessDeniedException.class);
        assertThat(projectCount(jdbc, "review_run", projectId)).isZero();
        long writeStarted = System.nanoTime();
        assertThat(coordinator.reviewProject(projectId, ownerName)).isEqualTo(ReviewCoordinator.Outcome.SUCCEEDED);
        long writeMillis = elapsedMillis(writeStarted);

        assertThat(projectCount(jdbc, "reviewed_commit", projectId)).isEqualTo(1);
        assertThat(projectCount(jdbc, "manual_review_file", projectId)).isEqualTo(FILE_COUNT);
        assertThat(projectCount(jdbc, "review_issue", projectId)).isEqualTo(FILE_COUNT);
        assertThat(jdbc.queryForMap("select commit_sha,coverage_type from reviewed_commit where project_id=?", projectId))
                .containsEntry("commit_sha", COMMIT_SHA).containsEntry("coverage_type", "MANUAL_ONLY");
        assertThat(jdbc.queryForObject("select last_reviewed_sha from project where id=?", String.class, projectId)).isEqualTo(COMMIT_SHA);
        assertThat(jdbc.queryForMap("select status,reviewed_commits from review_run where project_id=?", projectId))
                .containsEntry("status", "SUCCEEDED").containsEntry("reviewed_commits", 1);
        assertThat(jdbc.queryForObject("""
                select count(*) from review_issue i join manual_review_file m on m.id=i.manual_file_id
                    and m.project_id=i.project_id and m.reviewed_commit_id=i.reviewed_commit_id
                where i.project_id=? and i.assignee_id=? and i.issue_kind='MANUAL_REVIEW'
                    and i.severity is null and i.line_number is null and i.status='OPEN'
                    and i.resolution_note='' and i.assignment_reason='GITHUB_ACCOUNT'
                    and m.evidence_kind='PINNED_TREES' and m.reason_code='GIT_DIFF_BUDGET'
                    and m.old_object_sha is null and m.old_mode is null
                    and m.new_object_sha=? and m.new_mode='100644'
                """, Long.class, projectId, ownerId, OBJECT_SHA)).isEqualTo(FILE_COUNT);
        assertThat(jdbc.queryForList("select file_path from manual_review_file where project_id=? order by file_path", String.class, projectId))
                .containsExactlyElementsOf(files.stream().map(ManualReviewFile::filePath).toList());

        long pageStarted = System.nanoTime();
        Set<Long> seen = new HashSet<>();
        long previousId = Long.MAX_VALUE;
        int pageCount = FILE_COUNT / IssueService.PAGE_SIZE;
        for (int page = 0; page < pageCount; page++) {
            var result = issues.list(owner, "OPEN", page);
            assertThat(result.page()).isEqualTo(page);
            assertThat(result.issues()).hasSize(IssueService.PAGE_SIZE);
            assertThat(result.hasNext()).isEqualTo(page + 1 < pageCount);
            for (Map<String, Object> issue : result.issues()) {
                long id = ((Number) issue.get("id")).longValue();
                assertThat(id).isLessThan(previousId);
                assertThat(seen.add(id)).as("an issue appears exactly once across all owner pages").isTrue();
                previousId = id;
                assertThat(issue).containsEntry("issue_kind", "MANUAL_REVIEW").containsEntry("severity", null)
                        .containsEntry("assignee_username", ownerName).containsEntry("commit_sha", COMMIT_SHA)
                        .doesNotContainKeys("description", "suggestion", "resolution_note", "manual_old_object_sha", "manual_new_object_sha");
                assertThat((String) issue.get("description_preview")).hasSizeLessThanOrEqualTo(240);
            }
        }
        assertThat(seen).hasSize(FILE_COUNT);
        assertThat(issues.list(owner, "OPEN", pageCount).issues()).isEmpty();
        assertThat(issues.list(owner, "OPEN", pageCount).hasNext()).isFalse();
        assertThat(issues.list(owner, "RESOLVED", 0).issues()).isEmpty();
        assertThat(issues.list(outsider, "", 0).issues()).isEmpty();

        long firstId = seen.stream().mapToLong(Long::longValue).max().orElseThrow();
        long lastId = seen.stream().mapToLong(Long::longValue).min().orElseThrow();
        for (long id : List.of(firstId, lastId)) {
            for (ReviewActor actor : List.of(owner, admin)) {
                assertThat(issues.detail(id, actor)).containsEntry("project_id", projectId)
                        .containsEntry("manual_evidence_kind", "PINNED_TREES").containsEntry("manual_new_object_sha", OBJECT_SHA);
            }
            assertThatThrownBy(() -> issues.detail(id, outsider)).isInstanceOfSatisfying(ResponseStatusException.class,
                    error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        }
        // Admin pages include existing fixtures, so locate the new issue in the global ordering.
        long newerOpenIssues = jdbc.queryForObject("select count(*) from review_issue where status='OPEN' and id>?", Long.class, firstId);
        var adminPage = issues.list(admin, "OPEN", Math.toIntExact(newerOpenIssues / IssueService.PAGE_SIZE));
        assertThat(adminPage.issues()).hasSizeLessThanOrEqualTo(IssueService.PAGE_SIZE)
                .anySatisfy(issue -> assertThat(((Number) issue.get("id")).longValue()).isEqualTo(firstId));
        long pageMillis = elapsedMillis(pageStarted);

        long retryStarted = System.nanoTime();
        assertThat(coordinator.reviewProject(projectId, ownerName)).isEqualTo(ReviewCoordinator.Outcome.SUCCEEDED);
        long retryMillis = elapsedMillis(retryStarted);
        assertThat(projectCount(jdbc, "reviewed_commit", projectId)).isEqualTo(1);
        assertThat(projectCount(jdbc, "manual_review_file", projectId)).isEqualTo(FILE_COUNT);
        assertThat(projectCount(jdbc, "review_issue", projectId)).isEqualTo(FILE_COUNT);
        assertThat(projectCount(jdbc, "review_run", projectId)).isEqualTo(2);
        assertThat(jdbc.queryForList("select reviewed_commits from review_run where project_id=? order by id", Integer.class, projectId))
                .containsExactly(1, 0);
        assertThat(jdbc.queryForList("select status from review_run where project_id=? order by id", String.class, projectId))
                .containsOnly("SUCCEEDED");
        assertThat(jdbc.queryForObject("""
                select count(*) from audit_event a join reviewed_commit c on c.id=a.target_id
                where a.target_type='REVIEWED_COMMIT' and a.action='MANUAL_REVIEW_ASSIGNED' and c.project_id=?
                """, Long.class, projectId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from review_issue where project_id <> ?", Long.class, projectId))
                .isEqualTo(unrelatedIssueCount);
        verify(git, times(2)).batch(any(), any(), any(), anySet(), anyInt());
        verifyNoMoreInteractions(git);
        verifyNoInteractions(ai);
        System.out.printf(Locale.ROOT,
                "Manual review PostgreSQL smoke (synthetic; not a throughput guarantee): files=%d, evidence_rows=%d, issue_rows=%d, pages=%d, write_ms=%d, read_ms=%d, retry_ms=%d%n",
                FILE_COUNT, FILE_COUNT, FILE_COUNT, pageCount, writeMillis, pageMillis, retryMillis);
    }

    private static long account(JdbcTemplate jdbc, String username, String role) {
        return jdbc.queryForObject("""
                insert into app_user(username,password_hash,git_username,role,enabled,approval_status)
                values(?,'synthetic-unusable-hash',?,?,true,'APPROVED') returning id
                """, Long.class, username, username, role);
    }

    private static long projectCount(JdbcTemplate jdbc, String table, long projectId) {
        if (!Set.of("reviewed_commit", "manual_review_file", "review_issue", "review_run").contains(table)) {
            throw new IllegalArgumentException("Unsupported fixture table");
        }
        return jdbc.queryForObject("select count(*) from " + table + " where project_id=?", Long.class, projectId);
    }

    private static long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }
}
