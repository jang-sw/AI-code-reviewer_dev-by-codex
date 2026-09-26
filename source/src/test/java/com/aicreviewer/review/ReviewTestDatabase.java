package com.aicreviewer.review;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.UUID;

/** Runs the actual migration, with H2 only as fast SQL/transaction regression coverage. */
public final class ReviewTestDatabase implements AutoCloseable {
    public final JdbcTemplate jdbc;
    public final DataSourceTransactionManager transactionManager;
    private final SingleConnectionDataSource source;

    public ReviewTestDatabase() {
        source = new SingleConnectionDataSource("jdbc:h2:mem:review_" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE", "sa", "", true);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__initial_schema.sql"),
                new ClassPathResource("db/migration/V2__security_versions_and_audit_index.sql"),
                new ClassPathResource("db/migration/V3__git_author_mapping.sql"),
                new ClassPathResource("db/migration/V4__review_assignment_provenance.sql"),
                new ClassPathResource("db/migration/V5__review_coverage.sql"),
                new ClassPathResource("db/migration/V6__review_history_pagination_indexes.sql"),
                new ClassPathResource("db/migration/V7__account_approval.sql"),
                new ClassPathResource("db/migration/V8__project_list_indexes.sql"),
                new ClassPathResource("db/migration/V9__issue_list_indexes.sql"),
                new ClassPathResource("db/migration/V10__manual_review_evidence.sql"),
                new ClassPathResource("db/migration/V11__metadata_manual_reason.sql"),
                new ClassPathResource("db/migration/V12__durable_review_requests.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        transactionManager = new DataSourceTransactionManager(source);
        jdbc.update("insert into app_user(id, username, password_hash, git_username, role, enabled) values (1, 'owner', 'unused', 'owner-git', 'USER', true), (2, 'author', 'unused', 'author-git', 'USER', true), (3, 'admin', 'unused', 'admin-git', 'ADMIN', true), (4, 'other', 'unused', 'other-git', 'USER', true)");
        jdbc.update("insert into project(id, name, repository_url, provider, repository_host, repository_path, owner_id, status) values (10, 'Sample', 'https://github.com/org/sample', 'GITHUB', 'github.com', 'org/sample', 1, 'APPROVED')");
    }

    public long count(String table) { return jdbc.queryForObject("select count(*) from " + table, Long.class); }
    public String cursor() { return jdbc.queryForObject("select last_reviewed_sha from project where id = 10", String.class); }
    @Override public void close() { source.destroy(); }
}
