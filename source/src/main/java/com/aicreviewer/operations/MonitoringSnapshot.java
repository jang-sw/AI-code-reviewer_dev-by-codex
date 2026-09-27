package com.aicreviewer.operations;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Fixed public fields only; unavailable observations never masquerade as current zero counts. */
public record MonitoringSnapshot(String status, boolean available, Instant observedAt, Instant lastAttemptAt,
                                 Long ageSeconds, int delayedAfterMinutes, Map<String, Long> projectCounts,
                                 Map<String, Long> requestCounts, Instant oldestActiveRequestedAt,
                                 Long delayedActiveRequests, Long latestFailedProjects,
                                 boolean currentServerWorkerEnabled, boolean currentServerSchedulerEnabled) {
    static MonitoringSnapshot from(OperationsTelemetryService.Observation observation,
                                   boolean workerEnabled, boolean schedulerEnabled) {
        String status = switch (observation.status()) {
            case "READY", "STARTING", "FAILED", "STALE" -> observation.status();
            default -> "FAILED";
        };
        boolean available = "READY".equals(status);
        return new MonitoringSnapshot(status, available, observation.observedAt(), observation.lastAttemptAt(),
                observation.ageSeconds(), observation.delayedAfterMinutes(),
                available ? selected(observation.projectCounts(), "PENDING", "APPROVED", "REJECTED", "PAUSED") : Map.of(),
                available ? selected(observation.requestCounts(), "QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED") : Map.of(),
                available ? observation.oldestActiveRequestedAt() : null,
                available ? observation.delayedActiveRequests() : null,
                available ? observation.latestFailedProjects() : null, workerEnabled, schedulerEnabled);
    }

    private static Map<String, Long> selected(Map<String, Long> values, String... keys) {
        var selected = new LinkedHashMap<String, Long>();
        for (String key : keys) selected.put(key, values.get(key));
        return Map.copyOf(selected);
    }
}
