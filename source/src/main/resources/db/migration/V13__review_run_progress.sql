-- Older executions retain unknown stage/timestamps instead of invented progress.
ALTER TABLE review_run ADD COLUMN progress_stage VARCHAR(20)
    CHECK (progress_stage IN ('PREPARING', 'GIT_LOADING', 'REVIEWING', 'FINALIZING'));
ALTER TABLE review_run ADD COLUMN progress_updated_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE review_run ADD COLUMN last_saved_at TIMESTAMP WITH TIME ZONE;
