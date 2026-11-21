package com.github.safetytrick.sqlexplorer.evidence;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.environment.DatabaseServer;
import com.github.safetytrick.sqlexplorer.environment.Environment;
import com.github.safetytrick.sqlexplorer.environment.EnvironmentRegistry;
import com.github.safetytrick.sqlexplorer.environment.SourceDefinition;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlSource;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlSourceFactory;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import com.github.safetytrick.sqlexplorer.analysis.AnalysisFailureRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class EnvironmentLoader {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentLoader.class);

    private final Map<String, SqlSourceFactory> factoriesByType;
    private final SqlRepositoryManager sqlRepositoryManager;
    private final EnvironmentRegistry environmentRegistry;
    private final AnalysisFailureRecorder analysisFailureRecorder;
    private final Clock clock;
    private final Duration maxAge;
    private final boolean offline;

    private final Map<String, CacheFreshness> freshnessAtStartup = new ConcurrentHashMap<>();
    private final Set<String> pulling = ConcurrentHashMap.newKeySet();
    private final Map<String, EnvironmentHealth.PullAttempt> lastPull = new ConcurrentHashMap<>();

    public EnvironmentLoader(List<SqlSourceFactory> factories,
                             SqlRepositoryManager sqlRepositoryManager,
                             EnvironmentRegistry environmentRegistry,
                             AnalysisFailureRecorder analysisFailureRecorder) {
        this(factories, sqlRepositoryManager, environmentRegistry, analysisFailureRecorder, Clock.systemUTC(),
            Duration.ofHours(24), false);
    }

    @Autowired
    public EnvironmentLoader(List<SqlSourceFactory> factories,
                             SqlRepositoryManager sqlRepositoryManager,
                             EnvironmentRegistry environmentRegistry,
                             AnalysisFailureRecorder analysisFailureRecorder,
                             Clock clock,
                             @Value("${sql-explorer.cache.max-age:24h}") Duration maxAge,
                             @Value("${sql-explorer.cache.offline:false}") boolean offline) {
        this.factoriesByType = factories.stream()
            .collect(Collectors.toMap(SqlSourceFactory::type, Function.identity()));
        this.sqlRepositoryManager = sqlRepositoryManager;
        this.environmentRegistry = environmentRegistry;
        this.analysisFailureRecorder = analysisFailureRecorder;
        this.clock = clock;
        this.maxAge = maxAge;
        this.offline = offline;
    }

    void loadConfiguredSourcesAtStartup() {
        for (String name : startupOrder()) {
            Environment environment = environmentRegistry.get(name);
            if (environment != null && environment.isCached()) {
                continue;
            }
            try {
                loadConfiguredSources(name);
            } catch (RuntimeException e) {
                log.warn("Could not load environment {} at startup; it will be empty until "
                    + "something pulls it: {}", name, e.toString());
            }
        }
    }

    @PostConstruct
    void serveCapturesAtStartup() {
        for (String name : startupOrder()) {
            try {
                List<String> sourceRefs = buildConfiguredSources(name).stream().map(SqlSource::getSourceRef).toList();
                if (!sourceRefs.isEmpty()) {
                    sqlRepositoryManager.serveFromCaptures(name, sourceRefs);
                }
                decideCached(name, sourceRefs);
            } catch (RuntimeException e) {
                log.warn("Could not serve environment {} from its captures; it waits for its live pull: {}",
                    name, e.toString());
            }
        }
    }

    private void decideCached(String name, List<String> sourceRefs) {
        Environment environment = environmentRegistry.get(name);
        if (environment == null) {
            return;
        }
        CacheFreshness freshness = freshness(environment, sourceRefs);
        freshnessAtStartup.put(name, freshness);
        if (freshness.fresh() || offline) {
            environment.setCached(true);
            log.info("Environment {} is served from its cache and connects to nothing until pulled: {}", name,
                freshness.fresh() ? "oldest capture " + freshness.oldestCapture() + ", within " + maxAge
                    : "offline by configuration (sql-explorer.cache.offline); " + freshness.reasons());
        } else {
            log.info("Environment {} will be pulled live: {}", name, freshness.reasons());
        }
    }

    private CacheFreshness freshness(Environment environment, List<String> sourceRefs) {
        List<String> withoutSnapshot = environment.getClusters().stream()
            .filter(cluster -> cluster.schemaFrom().getFirst().lastKnownSchema().isEmpty())
            .map(com.github.safetytrick.sqlexplorer.environment.Cluster::name)
            .toList();
        return CacheFreshness.evaluate(sourceRefs, sqlRepositoryManager.servedCaptures(environment.getName()),
            withoutSnapshot, clock.instant(), maxAge);
    }

    public EnvironmentHealth health(String name) {
        Environment environment = environmentRegistry.get(name);
        List<String> problems = new ArrayList<>();
        List<String> configured;
        try {
            configured = buildConfiguredSources(name).stream().map(SqlSource::getSourceRef).toList();
        } catch (RuntimeException e) {
            configured = List.of();
            problems.add("its sources could not be built: " + e.getMessage());
        }
        if (configured.isEmpty() && problems.isEmpty()) {
            problems.add("no sources are configured, so there is nothing to serve");
        }
        Map<String, Instant> served = sqlRepositoryManager.servedCaptures(name);
        Map<String, String> failures = sqlRepositoryManager.getFailedSources(name).stream()
            .collect(Collectors.toMap(SqlRepositoryManager.SourceFailure::sourceRef,
                SqlRepositoryManager.SourceFailure::reason, (first, second) -> first));
        Instant now = clock.instant();
        Instant oldest = served.values().stream().filter(java.util.Objects::nonNull).min(Instant::compareTo)
            .orElse(null);

        boolean missing = false;
        for (String source : configured) {
            String failure = failures.get(source);
            if (!served.containsKey(source)) {
                missing = true;
                problems.add(source + ": nothing to serve" + (failure == null ? "" : " (" + failure + ")"));
            } else if (failure != null) {
                Instant at = served.get(source);
                problems.add(source + ": could not be read live (" + failure + "); serving its capture"
                    + (at == null ? "" : " from " + CacheFreshness.age(Duration.between(at, now)) + " ago"));
            }
        }
        boolean stale = oldest != null && oldest.isBefore(now.minus(maxAge));
        if (stale) {
            problems.add("the oldest data is " + CacheFreshness.age(Duration.between(oldest, now))
                + " old, over the " + CacheFreshness.age(maxAge) + " limit");
        }
        EnvironmentHealth.PullAttempt pull = lastPull.get(name);
        if (pull != null && !pull.succeeded()) {
            problems.add("the last pull failed: " + pull.error());
        }

        EnvironmentHealth.State state;
        if (pulling.contains(name)) {
            state = EnvironmentHealth.State.PULLING;
        } else if (served.isEmpty()) {
            state = EnvironmentHealth.State.UNAVAILABLE;
        } else if (missing) {
            state = EnvironmentHealth.State.PARTIAL;
        } else if (stale || (pull != null && !pull.succeeded()) || !failures.isEmpty()) {
            state = EnvironmentHealth.State.STALE;
        } else if (environment != null && environment.isCached()) {
            state = EnvironmentHealth.State.CACHED;
        } else {
            state = EnvironmentHealth.State.LIVE;
        }
        return new EnvironmentHealth(state, oldest, maxAge.toMinutes(), List.copyOf(problems), pull,
            configured.size());
    }

    public CacheFreshness freshnessAtStartup(String name) {
        return freshnessAtStartup.get(name);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void pullLiveOnceReady() {
        Thread pull = new Thread(this::loadConfiguredSourcesAtStartup, "startup-live-pull");
        pull.setDaemon(true);
        pull.start();
    }

    private List<String> startupOrder() {
        List<String> names = new ArrayList<>();
        names.add(environmentRegistry.getDefaultName());
        environmentRegistry.list().stream()
            .map(Environment::getName)
            .filter(name -> !names.contains(name))
            .forEach(names::add);
        return names;
    }

    public SqlImportStats loadConfiguredSources(String environmentName) {
        return load(environmentName, Stream.empty());
    }

    public SqlImportStats load(String environmentName, Stream<? extends SqlSource> extraSources) {
        Environment target = environmentRegistry.get(environmentName);
        if (target != null && target.isCached()) {
            log.info("Environment {} goes live: it was served from its cache", environmentName);
            target.setCached(false);
        }
        pulling.add(environmentName);
        if (target != null) {
            target.setPuller(Thread.currentThread());
        }
        try {
            SqlImportStats stats = loadLive(environmentName, extraSources);
            lastPull.put(environmentName, new EnvironmentHealth.PullAttempt(clock.instant(), true, null));
            return stats;
        } catch (RuntimeException e) {
            lastPull.put(environmentName, new EnvironmentHealth.PullAttempt(clock.instant(), false,
                rootMessage(e)));
            throw e;
        } finally {
            if (target != null) {
                target.setPuller(null);
            }
            pulling.remove(environmentName);
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        if (root.getMessage() == null) {
            return root.toString();
        }
        return root instanceof java.io.IOException && root.getClass().getName().startsWith("java.net.")
            ? root.getClass().getSimpleName() + ": " + root.getMessage() : root.getMessage();
    }

    private SqlImportStats loadLive(String environmentName, Stream<? extends SqlSource> extraSources) {
        analysisFailureRecorder.clear();
        Environment environment = environmentRegistry.get(environmentName);
        if (environment != null) {
            environment.revalidateSchemas();
        }
        List<SqlSource> sources = buildConfiguredSources(environmentName);
        List<SqlSource> all = Stream.concat(sources.stream(), extraSources).toList();

        if (all.isEmpty()) {
            log.info("No sources for environment {}; nothing to load", environmentName);
            return SqlImportStats.builder().build();
        }
        log.info("Loading {} source(s) into environment {} ({} configured)",
            all.size(), environmentName, sources.size());
        return sqlRepositoryManager.loadFromSqlSources(environmentName, all.stream());
    }

    private List<SqlSource> buildConfiguredSources(String environmentName) {
        Environment environment = environmentRegistry.get(environmentName);
        List<SqlSource> sources = new ArrayList<>();
        for (DatabaseServer server : environment.getServers()) {
            for (SourceDefinition definition : server.getSourceDefinitions()) {
                SqlSourceFactory factory = factoriesByType.get(definition.getType());
                if (factory == null) {
                    throw new IllegalArgumentException("Unknown source type '" + definition.getType()
                        + "' on server " + server.getName());
                }
                factory.create(environmentName, server, definition).forEach(sources::add);
            }
        }
        return sources;
    }
}
