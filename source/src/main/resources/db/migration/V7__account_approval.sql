ALTER TABLE app_user ADD COLUMN approval_status VARCHAR(16) NOT NULL DEFAULT 'APPROVED';
ALTER TABLE app_user ADD COLUMN approval_decided_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE app_user ADD COLUMN approval_reason VARCHAR(500);
ALTER TABLE app_user ADD CONSTRAINT app_user_approval_status_check
    CHECK (approval_status IN ('PENDING', 'APPROVED', 'REJECTED'));
ALTER TABLE app_user ADD CONSTRAINT app_user_unapproved_disabled_check
    CHECK (approval_status = 'APPROVED' OR (enabled = FALSE AND role = 'USER'));
CREATE INDEX app_user_approval_inbox_idx ON app_user(approval_status, id DESC);
