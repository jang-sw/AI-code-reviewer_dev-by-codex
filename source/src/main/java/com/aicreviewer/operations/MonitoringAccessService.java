package com.aicreviewer.operations;

import com.aicreviewer.identity.UserAccountService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Account authorization is current; operational aggregates are read exclusively from the cache. */
@Service
public class MonitoringAccessService {
    private final UserAccountService users;
    private final OperationsTelemetryService telemetry;
    private final boolean workerEnabled;
    private final boolean schedulerEnabled;

    public MonitoringAccessService(UserAccountService users, OperationsTelemetryService telemetry,
                                   @Value("${app.review.worker-enabled:true}") boolean workerEnabled,
                                   @Value("${app.review.enabled:true}") boolean schedulerEnabled) {
        this.users = users;
        this.telemetry = telemetry;
        this.workerEnabled = workerEnabled;
        this.schedulerEnabled = schedulerEnabled;
    }

    public MonitoringSnapshot snapshot(String username) {
        users.requireAdmin(username);
        return MonitoringSnapshot.from(telemetry.observation(), workerEnabled, schedulerEnabled);
    }
}
