package com.aicreviewer.review;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewProgressMigrationTest {
    @Test void upgradePreservesExistingExecutionRequestIssueAndCursorWithoutInventingProgress() {
        var source = new SingleConnectionDataSource("jdbc:h2:mem:progress_migration_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE", "sa", "", true);
        try {
            Flyway.configure().dataSource(source).target("12").load().migrate();
            var jdbc = new JdbcTemplate(source);
            String sha = "a".repeat(40);
            jdbc.update("insert into app_user(id,username,password_hash,git_username,role) values(1,'owner','fixture','owner','USER')");
            jdbc.update("insert into project(id,name,repository_url,provider,repository_host,repository_path,owner_id,status,last_reviewed_sha) " +
                    "values(1,'test','https://github.com/o/r','GITHUB','github.com','o/r',1,'APPROVED',?)", sha);
            jdbc.update("insert into review_run(id,project_id,status,reviewed_commits) values(1,1,'RUNNING',1)");
            jdbc.update("insert into review_request(project_id,request_id,claim_token,state,source,requested_by,requested_at,available_at,last_attempt_at,attempt_count,run_id) " +
                    "values(1,?,?,'RUNNING','MANUAL',1,current_timestamp,current_timestamp,current_timestamp,1,1)", UUID.randomUUID().toString(), UUID.randomUUID().toString());
            jdbc.update("insert into reviewed_commit(id,project_id,commit_sha,summary) values(1,1,?,'Existing result')", sha);
            jdbc.update("insert into review_issue(project_id,reviewed_commit_id,assignee_id,severity,title,file_path,description,suggestion,status) " +
                    "values(1,1,1,'LOW','Existing issue','A.java','Description','Suggestion','RESOLVED')");
            var run = jdbc.queryForMap("select id,project_id,status,started_at,finished_at,reviewed_commits,error_message from review_run");
            var request = jdbc.queryForList("select * from review_request");
            var project = jdbc.queryForList("select * from project");
            var commits = jdbc.queryForList("select * from reviewed_commit");
            var issues = jdbc.queryForList("select * from review_issue");

            Flyway.configure().dataSource(source).target("13").load().migrate();

            assertThat(jdbc.queryForMap("select id,project_id,status,started_at,finished_at,reviewed_commits,error_message from review_run")).isEqualTo(run);
            assertThat(jdbc.queryForMap("select progress_stage,progress_updated_at,last_saved_at from review_run"))
                    .containsEntry("progress_stage", null).containsEntry("progress_updated_at", null).containsEntry("last_saved_at", null);
            assertThat(jdbc.queryForList("select * from review_request")).isEqualTo(request);
            assertThat(jdbc.queryForList("select * from project")).isEqualTo(project);
            assertThat(jdbc.queryForList("select * from reviewed_commit")).isEqualTo(commits);
            assertThat(jdbc.queryForList("select * from review_issue")).isEqualTo(issues);
        } finally { source.destroy(); }
    }
}
