package com.aicreviewer.web;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class AuditController {
    private final JdbcTemplate jdbc;

    public AuditController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping("/admin/audit")
    @PreAuthorize("hasRole('ADMIN')")
    public String audit(@RequestParam(defaultValue = "0") int page, Model model) {
        if (page < 0 || page > 1_000_000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var events = jdbc.queryForList("""
                SELECT a.id, a.action, a.target_type, a.target_id, a.detail, a.created_at, u.username
                FROM audit_event a LEFT JOIN app_user u ON u.id = a.actor_id
                ORDER BY a.id DESC LIMIT 51 OFFSET ?
                """, page * 50);
        model.addAttribute("events", events.size() > 50 ? events.subList(0, 50) : events);
        model.addAttribute("hasNext", events.size() > 50);
        model.addAttribute("page", page);
        model.addAttribute("pageTitle", "감사 기록");
        return "admin/audit";
    }
}
