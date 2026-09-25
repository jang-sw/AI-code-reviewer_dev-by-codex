package com.aicreviewer.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicreviewer.issue.IssueService;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

class MetadataManualReasonMigrationTest {
    @Test void upgradesV10WithoutRewritingHistoricalMetadataIssuesOrExistingReasons() {
        var source = new SingleConnectionDataSource("jdbc:h2:mem:metadata_reason_" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE", "sa", "", true);
        try {
            Flyway.configure().dataSource(source).target("10").load().migrate();
            var jdbc = new JdbcTemplate(source);
            jdbc.update("insert into app_user(id,username,password_hash,git_username,role) values(1,'owner','fixture','owner','USER')");
            jdbc.update("insert into project(id,name,repository_url,provider,repository_host,repository_path,owner_id,status) values(1,'test','https://github.com/o/r','GITHUB','github.com','o/r',1,'APPROVED')");
            jdbc.update("insert into reviewed_commit(id,project_id,commit_sha,summary,coverage_type,coverage_details) values(1,1,?,'Legacy metadata notice','METADATA_ONLY','Original metadata evidence'),(2,1,?,'Manual review','MANUAL_ONLY','Pinned trees')", "a".repeat(40), "b".repeat(40));
            List<String> oldReasons = List.of("SOURCE_DIFF_UNAVAILABLE", "GIT_DIFF_BUDGET", "AI_INPUT_LIMIT");
            for (int index = 0; index < oldReasons.size(); index++) {
                jdbc.update("insert into manual_review_file(id,reviewed_commit_id,project_id,file_path,new_object_sha,new_mode,reason_code) values(?,2,1,?,?,'100644',?)", index + 1, "file-" + index, "c".repeat(40), oldReasons.get(index));
                jdbc.update("insert into review_issue(id,project_id,reviewed_commit_id,assignee_id,severity,title,file_path,description,suggestion,issue_kind,manual_file_id,status,resolution_note) values(?,1,2,1,NULL,'Manual task',?,'Description','Inspect','MANUAL_REVIEW',?,'RESOLVED','Previously verified')", index + 1, "file-" + index, index + 1);
            }
            var originalCommits = jdbc.queryForList("select * from reviewed_commit order by id");
            var originalIssues = jdbc.queryForList("select * from review_issue order by id");
            var originalFiles = jdbc.queryForList("select * from manual_review_file order by id");
            Flyway.configure().dataSource(source).load().migrate();
            assertThat(jdbc.queryForList("select * from reviewed_commit order by id")).isEqualTo(originalCommits);
            assertThat(jdbc.queryForList("select * from review_issue order by id")).isEqualTo(originalIssues);
            assertThat(jdbc.queryForList("select * from manual_review_file order by id")).isEqualTo(originalFiles);
            assertThat(jdbc.queryForObject("select count(*) from review_issue where reviewed_commit_id=1", Long.class)).isZero();
            assertThatThrownBy(() -> jdbc.update("update manual_review_file set reason_code='UNKNOWN' where id=1")).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("update manual_review_file set project_id=999 where id=1")).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("update review_issue set manual_file_id=999 where id=1")).isInstanceOf(DataIntegrityViolationException.class);
            jdbc.update("insert into manual_review_file(id,reviewed_commit_id,project_id,file_path,old_object_sha,new_object_sha,old_mode,new_mode,reason_code) values(4,2,1,'script.sh',?,?,'100644','100755','METADATA_CHANGE')", "d".repeat(40), "d".repeat(40));
            jdbc.update("insert into review_issue(id,project_id,reviewed_commit_id,assignee_id,severity,title,file_path,description,suggestion,issue_kind,manual_file_id) values(4,1,2,1,NULL,'Metadata manual task','script.sh','Description','Inspect','MANUAL_REVIEW',4)");
            assertThat(new IssueService(jdbc).detail(4, new ReviewActor(1, "owner", false)))
                    .containsEntry("manual_reason_code", "METADATA_CHANGE")
                    .containsEntry("manual_reason_label", "경로·권한·파일 유형 또는 검증된 빈 파일의 생성·삭제를 직접 확인해야 합니다.");
            assertThat(jdbc.queryForList("select reason_code from manual_review_file where id < 4 order by id", String.class)).isEqualTo(oldReasons);
        } finally { source.destroy(); }
    }
}
