package com.aicreviewer.operations;

import com.aicreviewer.identity.UserAccountService;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Read-only operational observations, not a guarantee about scheduled execution deadlines. */
@Service
public class OperationsService {
    public static final int PAGE_SIZE = 50;
    public static final int MAX_PAGE = 10000;
    private static final Set<String> FILTERS = Set.of("ATTENTION", "FAILED", "STALE", "NEVER_RUN", "QUEUED", "REQUEST_DELAYED");
    private final JdbcTemplate jdbc;
    private final UserAccountService users;
    private final int staleAfterMinutes;
    private final Clock clock;

    @Autowired
    public OperationsService(JdbcTemplate jdbc, UserAccountService users,
                             @Value("${app.operations.stale-after-minutes:120}") int staleAfterMinutes) {
        this(jdbc, users, staleAfterMinutes, Clock.systemUTC());
    }

    OperationsService(JdbcTemplate jdbc, UserAccountService users, int staleAfterMinutes, Clock clock) {
        if (staleAfterMinutes < 1 || staleAfterMinutes > 10080) {
            throw new IllegalArgumentException("Operational elapsed-time threshold must be between 1 and 10080 minutes");
        }
        this.jdbc = jdbc;
        this.users = users;
        this.staleAfterMinutes = staleAfterMinutes;
        this.clock = clock;
    }

    public OperationsPage list(String username, String filter, int page) {
        users.requireAdmin(username);
        if (page < 0 || page > MAX_PAGE || filter == null || !FILTERS.contains(filter)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "운영 현황 조회 조건을 확인해 주세요.");
        }
        Instant observedAt = clock.instant();
        Instant cutoff = observedAt.minus(Duration.ofMinutes(staleAfterMinutes));
        var args = new ArrayList<Object>();
        String condition = switch (filter) {
            case "FAILED" -> "r.status = 'FAILED'";
            case "NEVER_RUN" -> "p.status = 'APPROVED' AND r.id IS NULL";
            case "QUEUED" -> "q.state = 'QUEUED'";
            case "REQUEST_DELAYED" -> {
                args.add(Timestamp.from(cutoff));
                yield "q.state IN ('QUEUED', 'RUNNING') AND q.requested_at <= ?";
            }
            case "STALE" -> {
                args.add(Timestamp.from(cutoff));
                yield "p.status = 'APPROVED' AND r.id IS NOT NULL AND r.started_at <= ?";
            }
            default -> {
                args.add(Timestamp.from(cutoff));
                args.add(Timestamp.from(cutoff));
                yield "(r.status = 'FAILED' OR (p.status = 'APPROVED' AND (r.id IS NULL OR r.started_at <= ?)) OR (q.state IN ('QUEUED', 'RUNNING') AND q.requested_at <= ?))";
            }
        };
        args.add(PAGE_SIZE + 1);
        args.add((long) page * PAGE_SIZE);
        // The project-history index supports this latest-row lookup without materializing
        // every run in Java. Never select error text, repository addresses or provider settings.
        List<ProjectObservation> rows = jdbc.query("""
                SELECT p.id, p.name, p.status AS project_status,
                       r.id AS run_id, r.status AS run_status, r.started_at,
                       q.state AS request_state, q.requested_at
                FROM project p LEFT JOIN review_run r ON r.id = (
                    SELECT latest.id FROM review_run latest
                    WHERE latest.project_id = p.id ORDER BY latest.id DESC LIMIT 1
                )
                LEFT JOIN review_request q ON q.project_id = p.id
                """ + "WHERE " + condition + (filter.equals("QUEUED") || filter.equals("REQUEST_DELAYED")
                        ? " ORDER BY q.requested_at ASC, p.id ASC" : " ORDER BY r.started_at ASC NULLS FIRST, p.id ASC") + " LIMIT ? OFFSET ?",
                (rs, row) -> {
                    Long runId = rs.getObject("run_id", Long.class);
                    Timestamp started = rs.getTimestamp("started_at");
                    Instant startedAt = started == null ? null : started.toInstant();
                    String projectStatus = rs.getString("project_status");
                    String runStatus = rs.getString("run_status");
                    String requestState = rs.getString("request_state");
                    Timestamp requested = rs.getTimestamp("requested_at");
                    Instant requestedAt = requested == null ? null : requested.toInstant();
                    boolean approved = "APPROVED".equals(projectStatus);
                    return new ProjectObservation(rs.getLong("id"), rs.getString("name"), projectStatus, runId,
                            runStatus, startedAt, startedAt == null ? null : Math.max(0L, Duration.between(startedAt, observedAt).toMinutes()),
                            "FAILED".equals(runStatus), approved && startedAt != null && !startedAt.isAfter(cutoff), approved && runId == null,
                            requestState, requestedAt, requestedAt == null ? null : Math.max(0L, Duration.between(requestedAt, observedAt).toMinutes()),
                            ("QUEUED".equals(requestState) || "RUNNING".equals(requestState)) && requestedAt != null && !requestedAt.isAfter(cutoff));
                }, args.toArray());
        return new OperationsPage(List.copyOf(rows.subList(0, Math.min(PAGE_SIZE, rows.size()))), page,
                rows.size() > PAGE_SIZE && page < MAX_PAGE, filter, observedAt, staleAfterMinutes);
    }

    public record OperationsPage(List<ProjectObservation> projects, int page, boolean hasNext, String filter,
                                 Instant observedAt, int staleAfterMinutes) { }

    public record ProjectObservation(long projectId, String projectName, String projectStatus, Long runId,
                                     String runStatus, Instant startedAt, Long elapsedMinutes,
                                     boolean failed, boolean stale, boolean neverRun, String requestState,
                                     Instant requestedAt, Long requestElapsedMinutes, boolean requestDelayed) { }
}
