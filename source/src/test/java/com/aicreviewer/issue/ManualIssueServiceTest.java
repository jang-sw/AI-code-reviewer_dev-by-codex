package com.aicreviewer.issue;

import com.aicreviewer.review.ReviewRepository;
import com.aicreviewer.review.ReviewTestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.*;

class ManualIssueServiceTest {
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
        db.jdbc.update("INSERT INTO reviewed_commit(id,project_id,commit_sha,summary,coverage_type) VALUES(50,10,?,'Manual coverage','MANUAL_ONLY'),(51,10,?,'AI review','FULL')",
                "a".repeat(40), "b".repeat(40));
        db.jdbc.update("INSERT INTO manual_review_file(id,reviewed_commit_id,project_id,file_path,old_object_sha,new_object_sha,old_mode,new_mode,reason_code) VALUES(60,50,10,'assets/image.bin',?,?,'100644','100755','SOURCE_DIFF_UNAVAILABLE')",
                "c".repeat(40), "d".repeat(40));
        db.jdbc.update("INSERT INTO review_issue(id,project_id,reviewed_commit_id,assignee_id,severity,title,file_path,description,suggestion,issue_kind,manual_file_id) VALUES(100,10,50,2,NULL,'Manual task','assets/image.bin','Manual description','Inspect the file','MANUAL_REVIEW',60),(101,10,51,2,'HIGH','AI finding','Code.java','Description','Suggestion','AI_FINDING',NULL)");
    }

    @AfterEach void close() { db.close(); }

    @Test
    void manualListHasNoInventedSeverityAndDetailHasPinnedEvidenceOnlyForAuthorizedReaders() {
        var list = service.list(reviews.actor("author"), "OPEN", 0).issues();
        assertThat(list).hasSize(2);
        var manual = list.stream().filter(row -> row.get("id").equals(100L)).findFirst().orElseThrow();
        assertThat(manual).containsEntry("issue_kind", "MANUAL_REVIEW").containsEntry("severity", null).containsEntry("line_number", null);
        assertThat(manual).doesNotContainKeys("resolution_note", "description", "suggestion", "manual_old_object_sha");
        var detail = service.detail(100, reviews.actor("author"));
        assertThat(detail).containsEntry("manual_old_object_sha", "c".repeat(40)).containsEntry("manual_new_object_sha", "d".repeat(40))
                .containsEntry("manual_old_mode", "100644").containsEntry("manual_new_mode", "100755")
                .containsEntry("manual_evidence_kind", "PINNED_TREES").containsEntry("manual_reason_code", "SOURCE_DIFF_UNAVAILABLE");
        assertThat((String) detail.get("manual_reason_label")).contains("전체 변경 내용");
        assertThat(detail).doesNotContainKeys("repository_url", "author_email");
        assertThat(service.detail(100, reviews.actor("admin"))).containsEntry("issue_kind", "MANUAL_REVIEW");
        assertNotFound(() -> service.detail(100, reviews.actor("owner")));
        assertNotFound(() -> service.detail(100, reviews.actor("other")));
    }

    @ParameterizedTest @ValueSource(strings = {"OPEN", "RESOLVED", "DISMISSED"})
    void everyManualStateRequiresAReasonIncludingReopenAndLegacyThreeArgumentCalls(String status) {
        for (String reason : new String[] {null, "", "four", "  four  ", "valid\nreason", "valid\u0000reason", "x".repeat(1001)}) {
            assertThatThrownBy(() -> change(100, status, "author", reason))
                    .isInstanceOf(ManualIssueReasonException.class)
                    .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
        }
        assertThatThrownBy(() -> transactions.executeWithoutResult(tx -> service.changeStatus(100, status, reviews.actor("author"))))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(db.jdbc.queryForMap("SELECT status,resolution_note FROM review_issue WHERE id=100"))
                .containsEntry("status", "OPEN").containsEntry("resolution_note", "");
        assertThat(db.count("audit_event")).isZero();
    }

    @Test
    void manualCompletionReopenAndNotesKeepOriginalCoverageAndFileEvidence() {
        var evidence = db.jdbc.queryForList("SELECT * FROM manual_review_file");
        var commits = db.jdbc.queryForList("SELECT * FROM reviewed_commit ORDER BY id");
        change(100, "RESOLVED", "author", "파일 내용과 호출부를 직접 확인했습니다");
        change(100, "OPEN", "author", "새로운 사용 경로가 있어 다시 확인합니다");
        change(100, "DISMISSED", "admin", "배포에 포함되지 않는 예제 파일로 확인했습니다");
        assertThat(service.detail(100, reviews.actor("author")))
                .containsEntry("status", "DISMISSED").containsEntry("issue_kind", "MANUAL_REVIEW")
                .containsEntry("resolution_note", "배포에 포함되지 않는 예제 파일로 확인했습니다");
        assertThat(db.jdbc.queryForList("SELECT detail FROM audit_event ORDER BY id", String.class)).containsExactly(
                "OPEN -> RESOLVED; reason=파일 내용과 호출부를 직접 확인했습니다",
                "RESOLVED -> OPEN; reason=새로운 사용 경로가 있어 다시 확인합니다",
                "OPEN -> DISMISSED; reason=배포에 포함되지 않는 예제 파일로 확인했습니다");
        assertThat(db.jdbc.queryForList("SELECT * FROM manual_review_file")).isEqualTo(evidence);
        assertThat(db.jdbc.queryForList("SELECT * FROM reviewed_commit ORDER BY id")).isEqualTo(commits);
    }

    @Test
    void changingOnlyManualNoteIsAuditedAndIdenticalRepeatIsIdempotent() {
        change(100, "OPEN", "author", "확인 중이며 재현 테스트를 추가하고 있습니다");
        change(100, "OPEN", "author", "확인 중이며 재현 테스트를 추가하고 있습니다");
        assertThat(db.count("audit_event")).isEqualTo(1);
        assertThat(db.jdbc.queryForObject("SELECT action FROM audit_event", String.class)).isEqualTo("MANUAL_REVIEW_NOTE_UPDATED");
        change(100, "OPEN", "admin", "담당자와 검토 범위를 추가로 확인했습니다");
        assertThat(db.count("audit_event")).isEqualTo(2);
        assertThat(service.detail(100, reviews.actor("author"))).containsEntry("resolution_note", "담당자와 검토 범위를 추가로 확인했습니다");
    }

    @ParameterizedTest @ValueSource(ints = {5, 1000})
    void completeReasonBoundariesFitIssueAndAuditWithoutTruncation(int length) {
        String reason = "가".repeat(length);
        change(100, "RESOLVED", "author", reason);
        assertThat(db.jdbc.queryForObject("SELECT resolution_note FROM review_issue WHERE id=100", String.class)).isEqualTo(reason);
        assertThat(db.jdbc.queryForObject("SELECT detail FROM audit_event", String.class)).isEqualTo("OPEN -> RESOLVED; reason=" + reason);
    }

    @Test
    void unrelatedUsersCannotDiscoverManualValidationRulesOrChangeNotes() {
        for (String actor : new String[] {"owner", "other"}) {
            assertNotFound(() -> change(100, "RESOLVED", actor, ""));
            assertNotFound(() -> change(100, "RESOLVED", actor, "직접 확인했다고 주장하는 내용"));
            assertNotFound(() -> change(99999, "RESOLVED", actor, ""));
        }
        assertThat(db.jdbc.queryForMap("SELECT status,resolution_note FROM review_issue WHERE id=100"))
                .containsEntry("status", "OPEN").containsEntry("resolution_note", "");
        assertThat(db.count("audit_event")).isZero();
    }

    @Test
    void auditFailureRollsBackBothStatusAndReason() {
        db.jdbc.execute("ALTER TABLE audit_event ADD CONSTRAINT reject_manual_audit CHECK(action NOT IN ('ISSUE_STATUS_CHANGED','MANUAL_REVIEW_NOTE_UPDATED'))");
        for (String status : new String[] {"OPEN", "RESOLVED"}) {
            assertThatThrownBy(() -> change(100, status, "author", "검토 완료 내용이지만 감사 저장은 실패합니다"))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThat(db.jdbc.queryForMap("SELECT status,resolution_note FROM review_issue WHERE id=100"))
                .containsEntry("status", "OPEN").containsEntry("resolution_note", "");
        assertThat(db.count("audit_event")).isZero();
    }

    @Test
    void existingAiIssuesKeepTheThreeArgumentBehaviorAndIgnoreUnexpectedReasonInput() {
        transactions.executeWithoutResult(tx -> service.changeStatus(101, "RESOLVED", reviews.actor("author")));
        change(101, "RESOLVED", "author", "private unexpected fixture");
        change(101, "OPEN", "admin", "private unexpected fixture");
        assertThat(db.jdbc.queryForList("SELECT detail FROM audit_event ORDER BY id", String.class))
                .containsExactly("OPEN -> RESOLVED", "RESOLVED -> OPEN");
        assertThat(service.detail(101, reviews.actor("author"))).containsEntry("resolution_note", "").containsEntry("issue_kind", "AI_FINDING");
    }

    private void change(long id, String status, String actor, String reason) {
        transactions.executeWithoutResult(tx -> service.changeStatus(id, status, reviews.actor(actor), reason));
    }
    private static void assertNotFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
    }
}
