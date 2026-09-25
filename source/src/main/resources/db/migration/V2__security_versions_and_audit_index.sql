ALTER TABLE app_user ADD COLUMN security_version BIGINT NOT NULL DEFAULT 0;
CREATE INDEX audit_event_target_idx ON audit_event(target_type, target_id, action);
