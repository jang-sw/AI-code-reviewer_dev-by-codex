ALTER TABLE project ADD COLUMN next_review_at TIMESTAMP WITH TIME ZONE;
CREATE INDEX project_review_due_idx ON project(status, next_review_at, id);

CREATE TABLE review_request (
    project_id BIGINT PRIMARY KEY REFERENCES project(id),
    request_id VARCHAR(36) NOT NULL UNIQUE,
    claim_token VARCHAR(36),
    state VARCHAR(16) NOT NULL CHECK (state IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    source VARCHAR(16) NOT NULL CHECK (source IN ('MANUAL', 'SCHEDULED')),
    requested_by BIGINT REFERENCES app_user(id),
    requested_at TIMESTAMP WITH TIME ZONE NOT NULL,
    available_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_attempt_at TIMESTAMP WITH TIME ZONE,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    run_id BIGINT REFERENCES review_run(id),
    finished_at TIMESTAMP WITH TIME ZONE,
    result_code VARCHAR(40),
    CHECK ((source = 'MANUAL' AND requested_by IS NOT NULL) OR (source = 'SCHEDULED' AND requested_by IS NULL)),
    CHECK ((state = 'QUEUED' AND claim_token IS NULL AND run_id IS NULL AND finished_at IS NULL)
        OR (state = 'RUNNING' AND claim_token IS NOT NULL AND run_id IS NOT NULL AND finished_at IS NULL)
        OR (state IN ('SUCCEEDED', 'FAILED', 'CANCELLED') AND finished_at IS NOT NULL))
);
CREATE INDEX review_request_poll_idx ON review_request(state, available_at, requested_at, project_id);
