package com.aicreviewer.identity;

import com.aicreviewer.ai.ReviewFinding;
import com.aicreviewer.ai.ReviewResult;
import com.aicreviewer.git.GitCommit;
import com.aicreviewer.review.ReviewRepository;
import com.aicreviewer.review.ReviewTestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserGitUsernameServiceTest {
    private ReviewTestDatabase db;
    private UserAccountService users;
    private TransactionTemplate transactions;

    @BeforeEach void setup() {
        db = new ReviewTestDatabase();
        users = new UserAccountService(db.jdbc, new BCryptPasswordEncoder(4), new AuditEventWriter(db.jdbc));
        transactions = new TransactionTemplate(db.transactionManager);
    }

    @AfterEach void close() { db.close(); }

    @ParameterizedTest @ValueSource(strings = {"APPROVED", "PENDING", "REJECTED"})
    void correctionPreservesEveryOtherAccountFieldAndExistingAssignments(String approval) {
        db.jdbc.update("UPDATE app_user SET approval_status=?,enabled=?,approval_reason=?,security_version=7 WHERE id=2",
                approval, "APPROVED".equals(approval), "fixture existing reason");
        var before = new LinkedHashMap<>(db.jdbc.queryForMap("SELECT * FROM app_user WHERE id=2"));
        var reviews = new ReviewRepository(db.jdbc);
        save(reviews, "a", "author-git");
        var existingIssues = db.jdbc.queryForList("SELECT * FROM review_issue ORDER BY id");
        correct("admin", 2, "author-git", "  Corrected.User_2  ");
        before.put("git_username", "corrected.user_2");
        assertThat(db.jdbc.queryForMap("SELECT * FROM app_user WHERE id=2")).isEqualTo(before);
        assertThat(db.jdbc.queryForList("SELECT * FROM review_issue ORDER BY id")).isEqualTo(existingIssues);
        assertThat(db.jdbc.queryForMap("SELECT actor_id,action,target_type,target_id,detail FROM audit_event WHERE action='USER_GIT_USERNAME_CHANGED'"))
                .containsEntry("actor_id", 3L).containsEntry("target_id", 2L).containsEntry("target_type", "USER")
                .containsEntry("detail", "Git 계정 정정; 이전=author-git; 변경=corrected.user_2");
    }

    @Test void disabledApprovedAccountMayBeCorrectedWithoutReactivation() {
        db.jdbc.update("UPDATE app_user SET enabled=FALSE WHERE id=2");
        correct("admin", 2, "author-git", "corrected");
        assertThat(db.jdbc.queryForMap("SELECT git_username,enabled FROM app_user WHERE id=2"))
                .containsEntry("git_username", "corrected").containsEntry("enabled", false);
    }

    @Test void alreadySavedAssignmentsRemainAndNewAuthorLookupsUseTheCorrectedName() {
        var reviews = new ReviewRepository(db.jdbc);
        save(reviews, "a", "author-git");
        correct("admin", 2, "author-git", "corrected");
        save(reviews, "b", "author-git");
        save(reviews, "c", "corrected");
        assertThat(db.jdbc.queryForList("SELECT assignee_id FROM review_issue ORDER BY id", Long.class)).containsExactly(2L, 1L, 2L);
        assertThat(db.jdbc.queryForList("SELECT assignment_reason FROM review_issue ORDER BY id", String.class))
                .containsExactly("GITHUB_ACCOUNT", "PROJECT_OWNER_FALLBACK", "GITHUB_ACCOUNT");
    }

    @Test void staleExactOldValueAndDuplicateNameAreConflictsWithNoChanges() {
        for (String[] values : List.of(new String[] {"AUTHOR-GIT", "fresh"}, new String[] {"stale", "fresh"},
                new String[] {"author-git", "owner-git"})) {
            assertThatThrownBy(() -> correct("admin", 2, values[0], values[1]))
                    .isInstanceOfSatisfying(UserAccountService.GitUsernameChangeException.class,
                            error -> assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT));
        }
        assertThat(users.requireAccount("author").gitUsername()).isEqualTo("author-git");
        assertThat(db.count("audit_event")).isZero();
    }

    @Test void invalidOrUnchangedInputCannotBeWrittenOrAudited() {
        for (String input : new String[] {null, "", "@author", "contains space", "x".repeat(101), "\u0000hidden", "AUTHOR-GIT",
                "sk-" + "proj-" + "a".repeat(28), "gh" + "p_" + "a".repeat(30)}) {
            assertThatThrownBy(() -> correct("admin", 2, "author-git", input))
                    .isInstanceOfSatisfying(UserAccountService.GitUsernameChangeException.class,
                            error -> assertThat(error.status()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
        assertThat(db.count("audit_event")).isZero();
        assertThat(users.requireAccount("author").gitUsername()).isEqualTo("author-git");
    }

    @Test void shortPrefixNamesAndMaximumValidNamesRemainAllowed() {
        correct("admin", 2, "author-git", "sk-user");
        correct("admin", 2, "sk-user", "a".repeat(100));
        assertThat(users.requireAccount("author").gitUsername()).hasSize(100);
    }

    @Test void ordinaryAnonymousAndNoLongerAdminActorsAreDeniedBeforeInputValidation() {
        for (String actor : new String[] {null, "owner"}) {
            assertThatThrownBy(() -> correct(actor, 2, "author-git", null)).isInstanceOf(ResponseStatusException.class);
            assertThatThrownBy(() -> users.forAdmin(actor, 2)).isInstanceOf(ResponseStatusException.class);
        }
        db.jdbc.update("UPDATE app_user SET enabled=FALSE WHERE id=3");
        assertThatThrownBy(() -> correct("admin", 2, "author-git", "fresh"))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(401));
        assertThat(db.count("audit_event")).isZero();
    }

    @Test void missingTargetIsNotFoundAndAuditFailureRollsBackTheName() {
        assertThatThrownBy(() -> correct("admin", 99, "missing", "fresh"))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(404));
        var before = db.jdbc.queryForMap("SELECT * FROM app_user WHERE id=2");
        db.jdbc.execute("ALTER TABLE audit_event ADD CONSTRAINT reject_git_correction CHECK(action <> 'USER_GIT_USERNAME_CHANGED')");
        assertThatThrownBy(() -> correct("admin", 2, "author-git", "fresh")).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(db.jdbc.queryForMap("SELECT * FROM app_user WHERE id=2")).isEqualTo(before);
        assertThat(db.count("audit_event")).isZero();
    }

    private void correct(String actor, long target, String expected, String replacement) {
        transactions.executeWithoutResult(status -> users.changeGitUsername(actor, target, expected, replacement));
    }

    private void save(ReviewRepository reviews, String digit, String author) {
        transactions.executeWithoutResult(status -> {
            long run = reviews.startRun(10, null, Instant.now());
            reviews.persistCommit(run, reviews.project(10, false), null,
                    new GitCommit(digit.repeat(40), author, "synthetic", "diff"),
                    new ReviewResult("fixture", List.of(new ReviewFinding("LOW", "fixture", "a.txt", 1, "fixture", "fixture"))), Instant.now());
            reviews.finishRun(run, true, Instant.now(), null);
        });
    }
}
