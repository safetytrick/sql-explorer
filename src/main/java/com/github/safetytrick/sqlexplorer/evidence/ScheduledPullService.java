package com.github.safetytrick.sqlexplorer.evidence;

import java.util.List;

import com.github.safetytrick.sqlexplorer.environment.Environment;
import com.github.safetytrick.sqlexplorer.environment.EnvironmentRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

/**
 * Pulls each environment's sources on its own schedule.
 *
 * <p>WHY A SCHEDULE AT ALL. A capture used to happen once per process start, so
 * the sampling cadence was "however often someone restarts the app" — twelve
 * captures in a day on a machine being worked on, one a month on a stable
 * deployment. Neither is a sampling strategy, and Performance Schema is
 * cumulative and cleared on reset, so a capture is the only durable record that
 * a period existed. Measured on the live corpus: 128 of 4,311 profiles had not
 * run in thirty days, the oldest last seen nearly four months back. Every one of
 * those disappears at the next reset unless a capture recorded it (sqlx-ijz).
 *
 * <p>PER ENVIRONMENT, and off unless configured. Environments differ in how fast
 * their digest tables fill and how often they restart, so one global cadence
 * would be wrong for most of them; and a pull writes a file, so a deployment
 * that never asked for a schedule should not quietly start filling a disk.
 *
 * <p>Registered programmatically rather than with {@code @Scheduled}, because
 * the expressions come from configuration that is read at startup and there is
 * one per environment.
 */
@Component
public class ScheduledPullService implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(ScheduledPullService.class);

    private final EnvironmentRegistry registry;
    private final EnvironmentLoader loader;
    private final CaptureRetention retention;

    public ScheduledPullService(EnvironmentRegistry registry, EnvironmentLoader loader,
                                CaptureRetention retention) {
        this.registry = registry;
        this.loader = loader;
        this.retention = retention;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        List<Environment> scheduled = registry.list().stream()
            .filter(environment -> environment.getPullCron() != null && !environment.getPullCron().isBlank())
            .toList();
        if (scheduled.isEmpty()) {
            return;
        }
        // The registrar has no scheduler of its own when nothing used @Scheduled.
        registrar.setScheduler(java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "capture-pull");
            thread.setDaemon(true);
            return thread;
        }));
        for (Environment environment : scheduled) {
            String cron = environment.getPullCron();
            log.info("Environment {} will pull its sources on: {}", environment.getName(), cron);
            registrar.addTriggerTask(() -> pull(environment.getName()), new CronTrigger(cron));
        }
    }

    /**
     * One scheduled pull.
     *
     * <p>Never throws. A pull that fails — an unreachable server, a source that
     * cannot be read — must not kill the schedule, or one bad night silently
     * ends the sampling that everything downstream depends on.
     */
    void pull(String environmentName) {
        try {
            SqlImportStats stats = loader.loadConfiguredSources(environmentName);
            log.info("Scheduled pull of {} captured {} profiles", environmentName, stats.count());
            retention.prune();
        } catch (RuntimeException e) {
            log.warn("Scheduled pull of {} failed; the schedule continues: {}",
                environmentName, e.toString());
        }
    }
}
