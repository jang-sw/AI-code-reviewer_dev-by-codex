package com.aicreviewer.issue;

import com.aicreviewer.review.ReviewActor;
import com.aicreviewer.review.ReviewRepository;
import com.aicreviewer.review.ReviewTestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IssueServiceTest {
    private ReviewTestDatabase db;
    private IssueService service;
    private ReviewRepository reviews;
    private TransactionTemplate transactions;

    @BeforeEach
    void setup() {
        db = new ReviewTestDatabase();
        service = new IssueService(db.jdbc);
        reviews = new ReviewRepository(db.jdbc);
        transactions = new TransactionTemplate(db.transactionManager);
        db.jdbc.update("insert into reviewed_commit(id, project_id, commit_sha, author_login, summary) values (50, 10, ?, 'author-git', 'Summary')", "a".repeat(40));
        db.jdbc.update("insert into review_issue(id, project_id, reviewed_commit_id, assignee_id, severity, title, file_path, description, suggestion) values (100, 10, 50, 2, 'HIGH', 'Author finding', 'src/Code.java', 'Description', 'Suggestion'), (101, 10, 50, 1, 'LOW', 'Owner finding', 'README.md', 'Description', 'Suggestion')");
    }

    @AfterEach
    void cleanup() { db.close(); }

    @Test
    void ordinaryUsersSeeOnlyAssignedIssuesWhileAdminSeesAll() {
        var author = service.list(actor("author"), "OPEN", 0);
        assertThat(author.total()).isEqualTo(1);
        assertThat(author.issues().getFirst().get("title")).isEqualTo("Author finding");
        assertThat(service.list(actor("owner"), "OPEN", 0).total()).isEqualTo(1);
        assertThat(service.list(actor("other"), "", 0).issues()).isEmpty();
        assertThat(service.list(actor("admin"), "", 0).total()).isEqualTo(2);
    }

    @Test
    void ownerCannotMutateSomeoneElsesIssueAndFailedAttemptLeavesNoAudit() {
        assertThatThrownBy(() -> change(100, "RESOLVED", "owner")).isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value()).isEqualTo(404));
        assertThat(status(100)).isEqualTo("OPEN");
        assertThat(db.count("audit_event")).isZero();
    }

    @Test
    void assigneeCanResolveAndReopenWhileAdminCanDismiss() {
        change(100, "RESOLVED", "author");
        assertThat(status(100)).isEqualTo("RESOLVED");
        assertThat(service.list(actor("author"), "OPEN", 0).issues()).isEmpty();
        change(100, "OPEN", "author");
        change(100, "DISMISSED", "admin");
        assertThat(status(100)).isEqualTo("DISMISSED");
        assertThat(db.jdbc.queryForList("select detail from audit_event order by id", String.class))
                .containsExactly("OPEN -> RESOLVED", "RESOLVED -> OPEN", "OPEN -> DISMISSED");
    }

    @Test
    void identicalStatusIsIdempotentAndInvalidInputCannotMutateState() {
        change(100, "OPEN", "author");
        assertThat(db.count("audit_event")).isZero();
        assertThatThrownBy(() -> change(100, "DELETE", "admin")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.list(actor("author"), "INVALID", 0)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.list(actor("author"), "OPEN", -1)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.list(actor("author"), "OPEN", 10001)).isInstanceOf(ResponseStatusException.class);
        assertThat(status(100)).isEqualTo("OPEN");
        assertThat(db.count("audit_event")).isZero();
    }

    @Test
    void auditFailureRollsBackStatusMutation() {
        db.jdbc.execute("alter table audit_event add constraint reject_status_audit check (action <> 'ISSUE_STATUS_CHANGED')");
        assertThatThrownBy(() -> change(100, "RESOLVED", "author")).isInstanceOf(RuntimeException.class);
        assertThat(status(100)).isEqualTo("OPEN");
        assertThat(db.count("audit_event")).isZero();
    }

    @Test
    void fallbackAssignmentIsExplicitInViewData() {
        db.jdbc.update("insert into audit_event(action, target_type, target_id, detail) values ('ISSUE_ASSIGNEE_FALLBACK', 'REVIEWED_COMMIT', 50, 'Assigned to owner')");
        assertThat(service.list(actor("owner"), "OPEN", 0).issues().getFirst().get("fallback_assignment")).isEqualTo(true);
    }

    @Test
    void paginationHasStableNewestFirstOrderingAndBoundedPageSize() {
        for (long id = 102; id < 130; id++) {
            db.jdbc.update("insert into review_issue(id, project_id, reviewed_commit_id, assignee_id, severity, title, file_path, description, suggestion) values (?, 10, 50, 2, 'LOW', 'Finding', 'Code.java', 'Description', 'Suggestion')", id);
        }
        var first = service.list(actor("author"), "OPEN", 0);
        var second = service.list(actor("author"), "OPEN", 1);
        assertThat(first.issues()).hasSize(25);
        assertThat(first.issues().getFirst().get("id")).isEqualTo(129L);
        assertThat(first.total()).isEqualTo(29);
        assertThat(first.hasNext()).isTrue();
        assertThat(second.issues()).hasSize(4);
        assertThat(second.hasNext()).isFalse();
    }

    private ReviewActor actor(String username) { return reviews.actor(username); }
    private void change(long id, String status, String username) {
        transactions.executeWithoutResult(transaction -> service.changeStatus(id, status, actor(username)));
    }
    private String status(long id) { return db.jdbc.queryForObject("select status from review_issue where id = ?", String.class, id); }
}
