package com.aicreviewer.operations;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.ui.ExtendedModelMap;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MonitoringControllerTest {
    @Test void htmlUsesUtcSecondLabelsWhileRetainingOriginalTimestampsForJsonAndDatetime() {
        var access = mock(MonitoringAccessService.class);
        var controller = new MonitoringController(access);
        Instant observed = Instant.parse("2026-09-27T04:47:34.115279800Z");
        Instant attempted = observed.plusSeconds(30);
        Instant oldest = observed.minusSeconds(7200);
        var snapshot = new MonitoringSnapshot("READY", true, observed, attempted, 30L, 120,
                Map.of(), Map.of(), oldest, 1L, 0L, false, true);
        when(access.snapshot("admin")).thenReturn(snapshot);
        var model = new ExtendedModelMap();

        assertThat(controller.page(() -> "admin", model, new MockHttpServletResponse())).isEqualTo("admin/monitoring");
        assertThat(model).containsEntry("monitoringObservedAtLabel", "2026-09-27 04:47:34")
                .containsEntry("monitoringLastAttemptAtLabel", "2026-09-27 04:48:04")
                .containsEntry("monitoringOldestActiveRequestedAtLabel", "2026-09-27 02:47:34");
        assertThat(model.get("monitoring")).isSameAs(snapshot);
        assertThat(controller.snapshot(() -> "admin").getBody()).isSameAs(snapshot);
        assertThat(snapshot.observedAt()).isEqualTo(observed);

        var unavailable = new MonitoringSnapshot("FAILED", false, null, null, null, 120,
                Map.of(), Map.of(), null, null, null, false, true);
        when(access.snapshot("admin")).thenReturn(unavailable);
        var emptyModel = new ExtendedModelMap();
        controller.page(() -> "admin", emptyModel, new MockHttpServletResponse());
        assertThat(emptyModel).containsEntry("monitoringObservedAtLabel", "")
                .containsEntry("monitoringLastAttemptAtLabel", "")
                .containsEntry("monitoringOldestActiveRequestedAtLabel", "");
        assertThat(emptyModel.get("monitoring")).isSameAs(unavailable);
    }
}
