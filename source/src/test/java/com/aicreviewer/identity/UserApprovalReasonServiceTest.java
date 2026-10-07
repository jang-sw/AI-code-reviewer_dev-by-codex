package com.aicreviewer.identity;

import com.aicreviewer.review.ReviewTestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserApprovalReasonServiceTest {
    private static final long APPLICANT = 20;
    private ReviewTestDatabase db;
    private UserAccountService users;
    private TransactionTemplate transactions;

    @BeforeEach void setup() {
        db = new ReviewTestDatabase();
        users = new UserAccountService(db.jdbc, new BCryptPasswordEncoder(4), new AuditEventWriter(db.jdbc));
        transactions = new TransactionTemplate(db.transactionManager);
        db.jdbc.update("INSERT INTO app_user(id,username,password_hash,git_username,role,enabled,approval_status) " +
                "VALUES (?,'applicant','non-authenticating-fixture','applicant-git','USER',FALSE,'PENDING')", APPLICANT);
    }

    @AfterEach void close() { db.close(); }

    @Test void onlyAuthorizedAdminListsReceiveTheCurrentRejectionReason() {
        String reason = "<script>alert('fixture')</script> & 등록 정보를 확인해 주세요";
        decide("reject", "  " + reason + "  ");
        assertThat(users.list("admin", 0, "REJECTED", "applicant")).singleElement().satisfies(account -> {
            assertThat(account.id()).isEqualTo(APPLICANT);
            assertThat(account.approvalReason()).isEqualTo(reason);
        });
        assertThatThrownBy(() -> users.list("owner", 0, "REJECTED", "applicant"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(403));
        assertThatThrownBy(() -> users.list(null, 0, "REJECTED", "applicant"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(401));
        assertThatThrownBy(() -> users.requireAccount("applicant")).isInstanceOf(ResponseStatusException.class);

        // Even inconsistent legacy values on other approval states must not reach
        // ordinary account views, or appear as their current rejection reason.
        db.jdbc.update("UPDATE app_user SET approval_reason=? WHERE id IN (1,3)", reason);
        assertThat(users.requireAccount("owner").approvalReason()).isNull();
        assertThat(users.requireAdmin("admin").approvalReason()).isNull();
        assertThat(users.list("admin", 0, "APPROVED", "")).allSatisfy(account -> assertThat(account.approvalReason()).isNull());
        decide("reopen", "");
        assertThat(users.list("admin", 0, "PENDING", "applicant")).singleElement()
                .satisfies(account -> assertThat(account.approvalReason()).isNull());
    }

    @Test void repeatedRejectionsAndApprovalKeepEveryReasonInTheirOwnAuditEvents() {
        String first = "Git 계정의 소유 관계를 확인해 주세요";
        String second = "프로젝트 담당자 확인이 추가로 필요합니다";
        decide("reject", first);
        decide("reopen", "");
        decide("reject", second);
        decide("reopen", "");
        decide("approve", "");

        assertThat(users.requireAccount("applicant").approvalReason()).isNull();
        assertThat(db.jdbc.queryForObject("SELECT approval_reason FROM app_user WHERE id=?", String.class, APPLICANT)).isNull();
        assertThat(db.jdbc.queryForObject("SELECT security_version FROM app_user WHERE id=?", Long.class, APPLICANT)).isEqualTo(5L);
        assertThat(db.jdbc.queryForList("SELECT detail FROM audit_event WHERE target_type='USER' AND target_id=? ORDER BY id", String.class, APPLICANT))
                .containsExactly("PENDING → REJECTED; 반려 사유: " + first,
                        "REJECTED → PENDING; 이전 반려 사유: " + first,
                        "PENDING → REJECTED; 반려 사유: " + second,
                        "REJECTED → PENDING; 이전 반려 사유: " + second,
                        "PENDING → APPROVED");
    }

    @Test void reopeningALegacyRejectionArchivesItsOnlyRemainingReasonBeforeClearingIt() {
        String reason = "이전 버전에 저장된 관리자 확인 사유";
        db.jdbc.update("UPDATE app_user SET approval_status='REJECTED',approval_reason=?,approval_decided_at=CURRENT_TIMESTAMP WHERE id=?",
                reason, APPLICANT);
        db.jdbc.update("INSERT INTO audit_event(actor_id,action,target_type,target_id,detail) " +
                "VALUES (3,'USER_APPROVAL_REJECTED','USER',?,'PENDING → REJECTED')", APPLICANT);
        decide("reopen", "");
        assertThat(db.jdbc.queryForMap("SELECT approval_status,approval_reason,approval_decided_at FROM app_user WHERE id=?", APPLICANT))
                .containsEntry("approval_status", "PENDING").containsEntry("approval_reason", null).containsEntry("approval_decided_at", null);
        assertThat(db.jdbc.queryForList("SELECT detail FROM audit_event ORDER BY id", String.class))
                .containsExactly("PENDING → REJECTED", "REJECTED → PENDING; 이전 반려 사유: " + reason);
    }

    @ParameterizedTest @NullSource @ValueSource(strings = {"", "   "})
    void optionalEmptyReasonIsExplicitInAuditAndRemainsNullOnTheAccount(String input) {
        decide("reject", input);
        assertThat(users.list("admin", 0, "REJECTED", "applicant")).singleElement()
                .satisfies(account -> assertThat(account.approvalReason()).isNull());
        decide("reopen", "");
        assertThat(db.jdbc.queryForList("SELECT detail FROM audit_event ORDER BY id", String.class))
                .containsExactly("PENDING → REJECTED; 반려 사유: (입력하지 않음)",
                        "REJECTED → PENDING; 이전 반려 사유: (입력하지 않음)");
    }

    @Test void fiveHundredCharacterReasonIsPreservedWithoutTruncationInBothAuditEvents() {
        String reason = "가".repeat(500);
        decide("reject", reason);
        assertThat(users.list("admin", 0, "REJECTED", "applicant")).singleElement()
                .satisfies(account -> assertThat(account.approvalReason()).isEqualTo(reason));
        decide("reopen", "");
        assertThat(db.jdbc.queryForList("SELECT detail FROM audit_event ORDER BY id", String.class))
                .containsExactly("PENDING → REJECTED; 반려 사유: " + reason, "REJECTED → PENDING; 이전 반려 사유: " + reason);
    }

    @Test void oversizedOrControlCharacterReasonsCannotChangeStateOrWriteAnAudit() {
        var before = db.jdbc.queryForMap("SELECT * FROM app_user WHERE id=?", APPLICANT);
        for (String reason : new String[] {"가".repeat(501), "사유\n추가", "사유\r추가", "사유\u0000추가"}) {
            assertThatThrownBy(() -> decide("reject", reason)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(db.jdbc.queryForMap("SELECT * FROM app_user WHERE id=?", APPLICANT)).isEqualTo(before);
        assertThat(db.count("audit_event")).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"reject", "reopen"})
    void auditFailureRollsBackStatusReasonDecisionTimeAndSessionVersionTogether(String action) {
        if ("reopen".equals(action)) decide("reject", "되돌리기 실패 시 보존해야 하는 사유");
        var before = db.jdbc.queryForMap("SELECT * FROM app_user WHERE id=?", APPLICANT);
        var audits = db.jdbc.queryForList("SELECT * FROM audit_event ORDER BY id");
        String rejectedAction = "reopen".equals(action) ? "USER_APPROVAL_PENDING" : "USER_APPROVAL_REJECTED";
        db.jdbc.execute("ALTER TABLE audit_event ADD CONSTRAINT reject_approval_audit CHECK(action <> '" + rejectedAction + "')");
        assertThatThrownBy(() -> decide(action, "기록 저장 실패 시 승인 상태도 바뀌면 안 됩니다"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(db.jdbc.queryForMap("SELECT * FROM app_user WHERE id=?", APPLICANT)).isEqualTo(before);
        assertThat(db.jdbc.queryForList("SELECT * FROM audit_event ORDER BY id")).isEqualTo(audits);
    }

    @Test void ordinaryUserCannotChangeAReasonAndOldAccountConstructorsStayReasonFree() {
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> users.decideApproval("owner", APPLICANT, "reject", "private")))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(403));
        assertThat(db.count("audit_event")).isZero();
        var now = Instant.parse("2026-01-01T00:00:00Z");
        assertThat(new UserAccount(1, "owner", "owner-git", "USER", true, now).approvalReason()).isNull();
        assertThat(new UserAccount(1, "owner", "owner-git", "USER", false, now, "REJECTED").approvalReason()).isNull();
    }

    private void decide(String action, String reason) {
        transactions.executeWithoutResult(status -> users.decideApproval("admin", APPLICANT, action, reason));
    }
}
