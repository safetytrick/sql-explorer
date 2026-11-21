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
