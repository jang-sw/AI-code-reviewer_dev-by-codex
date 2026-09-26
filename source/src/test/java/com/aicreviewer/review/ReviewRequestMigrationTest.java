package com.aicreviewer.review;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.Timestamp;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewRequestMigrationTest {
    @Test void upgradePreservesHistoricalReviewsIssuesAndCursorWithoutInventingPendingRequests() {
        var source = new SingleConnectionDataSource("jdbc:h2:mem:request_migration_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE", "sa", "", true);
        try {
            Flyway.configure().dataSource(source).target("11").load().migrate();
            var jdbc = new JdbcTemplate(source);
            String sha = "a".repeat(40);
            jdbc.update("insert into app_user(id,username,password_hash,git_username,role) values(1,'owner','fixture','owner','USER')");
            jdbc.update("insert into project(id,name,repository_url,provider,repository_host,repository_path,owner_id,status,last_reviewed_sha) values(1,'test','https://github.com/o/r','GITHUB','github.com','o/r',1,'APPROVED',?)", sha);
            jdbc.update("insert into review_run(id,project_id,status) values(1,1,'RUNNING')");
            jdbc.update("insert into reviewed_commit(id,project_id,commit_sha,summary) values(1,1,?,'Existing result')", sha);
            jdbc.update("insert into review_issue(project_id,reviewed_commit_id,assignee_id,severity,title,file_path,description,suggestion,status) values(1,1,1,'HIGH','Existing issue','file','Description','Suggestion','RESOLVED')");
            var runs = jdbc.queryForList("select * from review_run");
            var commits = jdbc.queryForList("select * from reviewed_commit");
            var issues = jdbc.queryForList("select * from review_issue");
            Flyway.configure().dataSource(source).load().migrate();
            assertThat(jdbc.queryForList("select * from review_run")).isEqualTo(runs);
            assertThat(jdbc.queryForList("select * from reviewed_commit")).isEqualTo(commits);
            assertThat(jdbc.queryForList("select * from review_issue")).isEqualTo(issues);
            assertThat(jdbc.queryForObject("select last_reviewed_sha from project where id = 1", String.class)).isEqualTo(sha);
            assertThat(jdbc.queryForObject("select next_review_at from project where id = 1", Timestamp.class)).isNull();
            assertThat(jdbc.queryForObject("select count(*) from review_request", Long.class)).isZero();
        } finally { source.destroy(); }
    }
}
