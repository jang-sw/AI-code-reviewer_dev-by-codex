CREATE INDEX project_owner_history_idx ON project(owner_id, id DESC);
CREATE INDEX project_status_history_idx ON project(status, id DESC);
