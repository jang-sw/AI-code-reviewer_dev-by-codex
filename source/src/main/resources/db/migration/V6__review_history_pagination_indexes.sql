CREATE INDEX reviewed_commit_project_history_idx ON reviewed_commit(project_id, id DESC);
CREATE INDEX review_run_project_history_idx ON review_run(project_id, id DESC);
