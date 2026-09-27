package com.aicreviewer.operations;

import jakarta.servlet.http.HttpServletResponse;
import java.security.Principal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@PreAuthorize("hasRole('ADMIN')")
public class MonitoringController {
    private static final DateTimeFormatter UTC_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
            .withZone(ZoneOffset.UTC);
    private final MonitoringAccessService monitoring;

    public MonitoringController(MonitoringAccessService monitoring) { this.monitoring = monitoring; }

    @GetMapping("/admin/monitoring")
    public String page(Principal principal, Model model, HttpServletResponse response) {
        var snapshot = monitoring.snapshot(principal.getName());
        response.setHeader("Cache-Control", "no-store");
        model.addAttribute("pageTitle", "서버 상태");
        model.addAttribute("monitoring", snapshot);
        model.addAttribute("monitoringObservedAtLabel", timeLabel(snapshot.observedAt()));
        model.addAttribute("monitoringLastAttemptAtLabel", timeLabel(snapshot.lastAttemptAt()));
        model.addAttribute("monitoringOldestActiveRequestedAtLabel", timeLabel(snapshot.oldestActiveRequestedAt()));
        return "admin/monitoring";
    }

    @GetMapping(value = "/admin/monitoring/snapshot", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MonitoringSnapshot> snapshot(Principal principal) {
        var snapshot = monitoring.snapshot(principal.getName());
        return ResponseEntity.status(snapshot.available() ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(CacheControl.noStore()).body(snapshot);
    }

    private static String timeLabel(Instant value) { return value == null ? "" : UTC_SECONDS.format(value); }
}
