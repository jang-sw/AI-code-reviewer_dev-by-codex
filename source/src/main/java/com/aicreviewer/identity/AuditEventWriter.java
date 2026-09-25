package com.aicreviewer.identity;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class AuditEventWriter {
    private final JdbcTemplate jdbc;

    public AuditEventWriter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void write(Long actorId, String action, String targetType, Long targetId, String detail) {
        jdbc.update("""
                INSERT INTO audit_event(actor_id, action, target_type, target_id, detail, created_at)
                VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, actorId, action, targetType, targetId, detail);
    }
}
