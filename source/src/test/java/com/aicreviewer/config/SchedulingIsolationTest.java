package com.aicreviewer.config;

import com.aicreviewer.operations.OperationsTelemetryService;
import com.aicreviewer.identity.SharedAttemptStore;
import com.aicreviewer.review.ReviewCoordinator;
import com.aicreviewer.review.ReviewDispatcher;
import com.aicreviewer.review.ReviewRequestRepository;
import com.aicreviewer.review.ReviewScheduler;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SchedulingIsolationTest {
    @Test
    @Timeout(12)
    void configuredSchedulerPollsQueueWhileObservationReconciliationAndAuthCleanupAreBlocked() throws Exception {
        var entered = new CountDownLatch(3);
        var release = new CountDownLatch(1);
        var polledWhileBothBlocked = new CountDownLatch(1);
        var jdbc = mock(JdbcTemplate.class);
        var requests = mock(ReviewRequestRepository.class);
        var coordinator = mock(ReviewCoordinator.class);
        var transactions = mock(PlatformTransactionManager.class);
        var cleanupSource = mock(DataSource.class);
        when(jdbc.getDataSource()).thenReturn(cleanupSource);
        when(cleanupSource.getConnection()).thenAnswer(invocation -> {
            hold(entered, release);
            throw new java.sql.SQLException("Synthetic cleanup connection unavailable");
        });
        when(transactions.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());
        doAnswer(invocation -> {
            hold(entered, release);
            throw new DataAccessResourceFailureException("Synthetic observation released after scheduling assertion");
        }).when(jdbc).query(anyString(), any(RowCallbackHandler.class));
        when(requests.scheduledCandidates(any(Instant.class), anyInt())).thenAnswer(invocation -> {
            hold(entered, release);
            return List.of();
        });
        when(requests.candidates(any(Instant.class), anyInt())).thenAnswer(invocation -> {
            // A poll before both tasks enter, or after cleanup releases them, is not evidence.
            if (entered.getCount() == 0 && release.getCount() == 1) polledWhileBothBlocked.countDown();
            return List.of();
        });

        var properties = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"));
        String poolSize = properties.getProperty("spring.task.scheduling.pool.size");
        assertThat(poolSize).as("Production scheduler pool setting must be explicit").isNotBlank();
        var metrics = new SimpleMeterRegistry();
        try {
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
                    .withUserConfiguration(ProductionScheduledServices.class)
                    .withPropertyValues("spring.task.scheduling.pool.size=" + poolSize,
                            "spring.threads.virtual.enabled=false", "app.review.enabled=true",
                            "app.review.worker-enabled=true", "app.review.concurrency=1",
                            "app.review.cron=0 0 * * * *", "app.operations.stale-after-minutes=120")
                    .withBean(JdbcTemplate.class, () -> jdbc)
                    .withBean(PlatformTransactionManager.class, () -> transactions)
                    .withBean(MeterRegistry.class, () -> metrics)
                    .withBean(ReviewRequestRepository.class, () -> requests)
                    .withBean(ReviewCoordinator.class, () -> coordinator)
                    .withBean(DataSource.class, () -> mock(DataSource.class))
                    .run(context -> {
                        try {
                            assertThat(context).hasNotFailed();
                            assertThat(context.getBean(TaskScheduler.class)).isInstanceOf(ThreadPoolTaskScheduler.class);
                            // Execute the actual cleanup method immediately instead of waiting for its minute interval.
                            context.getBean(TaskScheduler.class).schedule(new SharedAttemptStore(jdbc, transactions)::purgeExpired, Instant.now());
                            assertThat(entered.await(3, TimeUnit.SECONDS))
                                    .as("Actual telemetry, reconciliation and authentication cleanup methods entered").isTrue();
                            assertThat(polledWhileBothBlocked.await(7, TimeUnit.SECONDS))
                                    .as("A new scheduled queue poll runs while all three other tasks remain blocked").isTrue();
                            verifyNoInteractions(coordinator);
                        } finally {
                            release.countDown();
                        }
                    });
        } finally {
            release.countDown();
            metrics.close();
        }
    }

    private static void hold(CountDownLatch entered, CountDownLatch release) throws InterruptedException {
        entered.countDown();
        if (!release.await(11, TimeUnit.SECONDS)) throw new IllegalStateException("Scheduling fixture was not released");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @Import({OperationsTelemetryService.class, ReviewScheduler.class, ReviewDispatcher.class})
    static class ProductionScheduledServices {}
}
