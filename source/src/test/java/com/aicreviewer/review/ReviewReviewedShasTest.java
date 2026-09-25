package com.aicreviewer.review;

import com.aicreviewer.git.GitRepositoryClient;
import com.aicreviewer.git.IntegrationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReviewReviewedShasTest {
    @Test
    void durableProgressIsProjectScopedAndImmutable() {
        try (var db = new ReviewTestDatabase()) {
            var repository = new ReviewRepository(db.jdbc);
            db.jdbc.update("insert into project(id, name, repository_url, provider, repository_host, repository_path, owner_id, status) values (11, 'Other', 'https://github.com/org/other', 'GITHUB', 'github.com', 'org/other', 4, 'APPROVED')");
            db.jdbc.update("insert into reviewed_commit(project_id, commit_sha, summary) values (10, ?, 'Reviewed'), (10, ?, 'Reviewed'), (11, ?, 'Other')",
                    "a".repeat(40), "b".repeat(64), "c".repeat(40));

            var shas = repository.reviewedShas(10);

            assertThat(shas).containsExactlyInAnyOrder("a".repeat(40), "b".repeat(64));
            assertThat(repository.reviewedShas(11)).containsExactly("c".repeat(40));
            assertThat(repository.reviewedShas(999)).isEmpty();
            assertThatThrownBy(() -> shas.add("d".repeat(40))).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Test
    void repeatedDurableRowsCannotInflateTheProgressSet() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(String.class), eq(10L), anyInt()))
                .thenReturn(List.of("a".repeat(40), "a".repeat(40), "b".repeat(64)));

        assertThat(new ReviewRepository(jdbc).reviewedShas(10)).containsExactlyInAnyOrder("a".repeat(40), "b".repeat(64));
    }

    @Test
    void exactProgressBudgetIsAcceptedAndJdbcReceivesABoundedQuery() {
        var jdbc = mock(JdbcTemplate.class);
        // Exercise the real bound without creating 131,072 SQL rows or a second in-memory input copy.
        List<String> rows = new AbstractList<>() {
            @Override public String get(int index) {
                String suffix = Integer.toHexString(index);
                return "0".repeat(40 - suffix.length()) + suffix;
            }
            @Override public int size() { return GitRepositoryClient.MAX_REVIEWED_SHAS; }
        };
        when(jdbc.queryForList(anyString(), eq(String.class), eq(10L), anyInt())).thenReturn(rows);

        var shas = new ReviewRepository(jdbc).reviewedShas(10);

        assertThat(shas).hasSize(GitRepositoryClient.MAX_REVIEWED_SHAS).contains(rows.getFirst(), rows.getLast());
        verify(jdbc).queryForList(argThat(sql -> sql.contains("where project_id = ?") && sql.endsWith("limit ?")),
                eq(String.class), eq(10L), eq(GitRepositoryClient.MAX_REVIEWED_SHAS + 1));
    }

    @Test
    void exceedingTheProgressBudgetFailsBeforeReadingOrTruncatingRows() {
        var jdbc = mock(JdbcTemplate.class);
        List<String> rows = new AbstractList<>() {
            @Override public String get(int index) { throw new AssertionError("Over-budget rows must not be consumed"); }
            @Override public int size() { return GitRepositoryClient.MAX_REVIEWED_SHAS + 1; }
        };
        when(jdbc.queryForList(anyString(), eq(String.class), eq(10L), anyInt())).thenReturn(rows);

        assertThatThrownBy(() -> new ReviewRepository(jdbc).reviewedShas(10)).isInstanceOf(IntegrationException.class)
                .hasMessageContaining("memory safety budget");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "main", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "private@example.test" })
    void invalidStoredShaFailsClosedWithoutIncludingItsContentsInTheError(String sha) {
        var jdbc = mock(JdbcTemplate.class);
        var rows = new ArrayList<String>();
        rows.add(sha);
        when(jdbc.queryForList(anyString(), eq(String.class), eq(10L), anyInt())).thenReturn(rows);

        assertThatThrownBy(() -> new ReviewRepository(jdbc).reviewedShas(10)).isInstanceOf(IntegrationException.class)
                .hasMessage("Durable review progress contains an invalid commit identifier");
    }
}
