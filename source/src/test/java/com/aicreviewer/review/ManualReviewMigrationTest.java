package com.aicreviewer.review;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import static org.assertj.core.api.Assertions.*;

class ManualReviewMigrationTest {
    @Test void upgradesV9WithoutRelabelingHistoricalCoverageOrIssues() {
        var source = new SingleConnectionDataSource("jdbc:h2:mem:manual_migration_" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE", "sa", "", true);
        try {
            Flyway.configure().dataSource(source).target("9").load().migrate();
            var jdbc = new JdbcTemplate(source);
            jdbc.update("insert into app_user(id,username,password_hash,git_username,role) values(1,'owner','fixture','owner','USER')");
            jdbc.update("insert into project(id,name,repository_url,provider,repository_host,repository_path,owner_id,status) values(1,'test','https://github.com/o/r','GITHUB','github.com','o/r',1,'APPROVED')");
            int id = 0;
            for (String coverage : java.util.List.of("FULL", "EMPTY", "METADATA_ONLY")) {
                id++;
                jdbc.update("insert into reviewed_commit(id,project_id,commit_sha,summary,coverage_type,coverage_details) values(?,1,?,'retained summary',?,'retained evidence')", id, Integer.toString(id).repeat(40), coverage);
            }
            jdbc.update("insert into review_issue(project_id,reviewed_commit_id,assignee_id,severity,title,file_path,description,suggestion,status) values(1,1,1,'HIGH','retained finding','A.java','description','suggestion','RESOLVED')");
            Flyway.configure().dataSource(source).load().migrate();
            assertThat(jdbc.queryForList("select coverage_type from reviewed_commit order by id", String.class)).containsExactly("FULL", "EMPTY", "METADATA_ONLY");
            assertThat(jdbc.queryForList("select coverage_details from reviewed_commit", String.class)).containsOnly("retained evidence");
            assertThat(jdbc.queryForMap("select issue_kind,status,severity,resolution_note,manual_file_id from review_issue"))
                    .containsEntry("issue_kind", "AI_FINDING").containsEntry("status", "RESOLVED").containsEntry("severity", "HIGH")
                    .containsEntry("resolution_note", "").containsEntry("manual_file_id", null);
            assertThat(jdbc.queryForObject("select count(*) from manual_review_file", Long.class)).isZero();
            assertThatThrownBy(() -> jdbc.update("update reviewed_commit set coverage_type='UNKNOWN' where id=1"))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        } finally { source.destroy(); }
    }
}
