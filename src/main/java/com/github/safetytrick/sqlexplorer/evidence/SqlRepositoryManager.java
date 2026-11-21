package com.github.safetytrick.sqlexplorer.evidence;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.environment.Environment;
import com.github.safetytrick.sqlexplorer.environment.EnvironmentRegistry;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlSource;
import com.github.safetytrick.sqlexplorer.schema.SqlSchemaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SqlRepositoryManager {

    private static final Logger log = LoggerFactory.getLogger(SqlRepositoryManager.class);

    private final SqlEvidenceInputService sqlEvidenceInputService;
    private final EnvironmentRegistry environmentRegistry;
    private final CaptureRehydrator captureRehydrator;
    private final Clock clock;

    private final int residentEnvironments;
    private final Map<String, EnvironmentRepositories> byEnvironment = new ConcurrentHashMap<>();

    private final Map<String, Instant> lastAccess = new ConcurrentHashMap<>();

    private final Map<String, List<SourceFailure>> failedSources = new ConcurrentHashMap<>();

    private final Map<String, List<String>> evicted = new ConcurrentHashMap<>();

    private final Map<String, ReentrantLock> rehydrationLocks = new ConcurrentHashMap<>();

    private final Map<String, Map<String, Instant>> servedCaptures = new ConcurrentHashMap<>();

    public SqlRepositoryManager(SqlEvidenceInputService sqlEvidenceInputService,
                                EnvironmentRegistry environmentRegistry,
                                CaptureRehydrator captureRehydrator,
                                @org.springframework.beans.factory.annotation.Value(
                                    "${sql-explorer.resident-environments:3}") int residentEnvironments,
                                Clock clock) {
        this.residentEnvironments = residentEnvironments;
        this.sqlEvidenceInputService = sqlEvidenceInputService;
        this.environmentRegistry = environmentRegistry;
        this.captureRehydrator = captureRehydrator;
        this.clock = clock;
    }

    public SqlRepository getSqlRepository(String environmentName) {
        return resident(environmentName).composite();
    }

    public SqlRepository getSqlRepository(String environmentName, SourceScope scope) {
        return resident(environmentName).scoped(scope);
    }

    public List<SqlRepository> getRepositories(String environmentName) {
        return resident(environmentName).repositories();
    }

    private EnvironmentRepositories resident(String environmentName) {
        if (environmentName == null) {
            return EnvironmentRepositories.EMPTY;
        }
        lastAccess.put(environmentName, clock.instant());
        EnvironmentRepositories loaded = byEnvironment.get(environmentName);
        if (loaded != null) {
            return loaded;
        }
        List<String> sourceRefs = evicted.get(environmentName);
        if (sourceRefs == null) {
            return EnvironmentRepositories.EMPTY;
        }

        ReentrantLock lock = rehydrationLocks.computeIfAbsent(environmentName, name -> new ReentrantLock());
        lock.lock();
        try {
            loaded = byEnvironment.get(environmentName);
            if (loaded != null) {
                return loaded;
            }
            if (rehydrate(environmentName, sourceRefs)) {
                evicted.remove(environmentName);
                return byEnvironment.getOrDefault(environmentName, EnvironmentRepositories.EMPTY);
            }
            log.warn("Environment {} was evicted and cannot be rehydrated; it must be loaded again",
                environmentName);
            return EnvironmentRepositories.EMPTY;
        } finally {
            lock.unlock();
        }
    }

    public LoadState getLoadState(String environmentName) {
        EnvironmentRepositories repositories = byEnvironment.get(environmentName);
        if (repositories == null) {
            List<String> sourceRefs = evicted.get(environmentName);
            return sourceRefs == null ? null : new LoadState(sourceRefs.size(), null, null, false);
        }
        return new LoadState(repositories.repositories().size(),
            repositories.loadedOn(),
            repositories.composite().getImportStats(),
            true);
    }

    public SqlImportStats loadFromSqlSources(Stream<? extends SqlSource> sources) {
        return loadFromSqlSources(environmentRegistry.getDefaultName(), sources);
    }

    public SqlImportStats loadFromSqlSources(String environmentName, Stream<? extends SqlSource> sources) {
        Environment environment = environmentRegistry.get(environmentName);
        SqlSchemaRepository schema = environment.schemaRepository();
        List<? extends SqlSource> sourceList = sources.toList();

        AnalysisReuse index = reuseFor(environmentName, sourceList.stream().map(SqlSource::getSourceRef).toList());

        List<SourceResult> results = sourceList.parallelStream().map(source -> {
            log.info("Loading source into environment {}: {}", environmentName, source);
            try {
                boolean schemaCurrent = environment.isSchemaCurrentFor(source.getSourceRef());
                if (!schemaCurrent) {
                    log.warn("Schema for source {} could not be confirmed current; statements not analysed "
                        + "before will wait for a load that can confirm it", source.getSourceRef());
                }
                return SourceResult.loaded(sqlEvidenceInputService.fromPerformanceSchema(source, source.getSql(),
                    schema, new SqlEvidenceInputService.AnalysisContext(index, schemaCurrent)));
            } catch (RuntimeException e) {
                log.warn("Source {} could not be read into environment {}; it is left out of this load: {}",
                    source.getSourceRef(), environmentName, e.toString());
                return SourceResult.failed(source.getSourceRef(), e);
            }
        }).toList();

        List<SqlRepository> repositories = new java.util.ArrayList<>(results.stream()
            .map(SourceResult::repository)
            .filter(java.util.Objects::nonNull)
            .toList());
        List<SourceFailure> failures = results.stream()
            .map(SourceResult::failure)
            .filter(java.util.Objects::nonNull)
            .toList();
        if (repositories.isEmpty() && !failures.isEmpty()) {
            failedSources.put(environmentName, failures);
            throw results.stream().map(SourceResult::cause).filter(java.util.Objects::nonNull)
                .findFirst().orElseThrow();
        }
        for (SourceFailure failure : failures) {
            SqlRepository previous = previouslyServed(environmentName, failure.sourceRef());
            if (previous != null) {
                repositories.add(previous);
            }
        }
        failedSources.put(environmentName, failures);

        recordServed(environmentName, repositories);
        byEnvironment.put(environmentName, new EnvironmentRepositories(List.copyOf(repositories),
            new CompositeSqlRepository(repositories), clock.instant()));
        lastAccess.put(environmentName, clock.instant());
        evicted.remove(environmentName);
        evictDownTo(environmentName);

        SqlImportStats stats = getSqlRepository(environmentName).getImportStats();
        log.info("Import stats for environment {}: {}", environmentName, stats);
        return stats;
    }

    public boolean rehydrate(String environmentName, List<String> sourceRefs) {
        if (sourceRefs == null || sourceRefs.isEmpty()) {
            return false;
        }
        List<SqlRepository> repositories = new java.util.ArrayList<>();
        for (String sourceRef : sourceRefs) {
            SqlRepository repository = captureRehydrator.rehydrate(sourceRef);
            if (repository == null) {
                log.info("Cannot rehydrate environment {}: source {} has no usable capture",
                    environmentName, sourceRef);
                return false;
            }
            repositories.add(repository);
        }
        recordServed(environmentName, repositories);
        byEnvironment.put(environmentName, new EnvironmentRepositories(List.copyOf(repositories),
            new CompositeSqlRepository(repositories), clock.instant()));
        lastAccess.put(environmentName, clock.instant());
        evictDownTo(environmentName);
        log.info("Rehydrated environment {} from {} capture(s)", environmentName, repositories.size());
        return true;
    }

    private AnalysisReuse reuseFor(String environmentName, List<String> sourceRefs) {
        AnalysisReuse.Builder index = new AnalysisReuse.Builder();
        java.util.Set<String> held = new java.util.HashSet<>();
        EnvironmentRepositories resident = byEnvironment.get(environmentName);
        if (resident != null) {
            for (SqlRepository repository : resident.repositories()) {
                if (repository.getSource() != null) {
                    held.add(repository.getSource().getSourceRef());
                }
                index.addAll(repository.getAllAnalyzedSql()
                    .flatMap(profile -> profile instanceof com.github.safetytrick.sqlexplorer.model.MergedSqlProfile merged
                        ? merged.members().stream() : java.util.stream.Stream.of(profile))
                    .toList());
            }
        }
        List<String> fromCaptures = sourceRefs.stream().filter(ref -> !held.contains(ref)).toList();
        if (captureRehydrator != null && !fromCaptures.isEmpty()) {
            AnalysisReuse captured = captureRehydrator.reuseFor(fromCaptures);
            if (captured != null) {
                index.addAll(captured);
            }
        }
        return index.build();
    }

    public int serveFromCaptures(String environmentName, List<String> sourceRefs) {
        List<SqlRepository> repositories = new java.util.ArrayList<>();
        List<SourceFailure> missing = new java.util.ArrayList<>();
        for (String sourceRef : sourceRefs) {
            SqlRepository repository = captureRehydrator.rehydrate(sourceRef);
            if (repository == null) {
                missing.add(new SourceFailure(sourceRef,
                    "no usable capture yet; it is read live by the pull that follows startup"));
            } else {
                repositories.add(repository);
            }
        }
        if (repositories.isEmpty()) {
            return 0;
        }
        recordServed(environmentName, repositories);
        byEnvironment.put(environmentName, new EnvironmentRepositories(List.copyOf(repositories),
            new CompositeSqlRepository(repositories), clock.instant()));
        failedSources.put(environmentName, List.copyOf(missing));
        lastAccess.put(environmentName, clock.instant());
        evicted.remove(environmentName);
        evictDownTo(environmentName);
        log.info("Serving environment {} from {} capture(s) until its live pull completes; {} without one",
            environmentName, repositories.size(), missing.size());
        return repositories.size();
    }

    private void evictDownTo(String keep) {
        if (residentEnvironments <= 0) {
            return;
        }
        while (byEnvironment.size() > residentEnvironments) {
            String oldest = byEnvironment.keySet().stream()
                .filter(name -> !name.equals(keep))
                .filter(this::canEvict)
                .min(Comparator.comparing(name -> lastAccess.getOrDefault(name, Instant.EPOCH)))
                .orElse(null);
            if (oldest == null) {
                return;
            }
            List<String> sourceRefs = residentSourceRefs(oldest);
            byEnvironment.remove(oldest);
            evicted.put(oldest, sourceRefs);
            log.info("Evicted environment {} ({} sources); it rebuilds from its captures on next use",
                oldest, sourceRefs.size());
        }
    }

    private boolean canEvict(String environmentName) {
        List<String> sourceRefs = residentSourceRefs(environmentName);
        return !sourceRefs.isEmpty() && sourceRefs.stream().allMatch(captureRehydrator::canRehydrate);
    }

    private List<String> residentSourceRefs(String environmentName) {
        EnvironmentRepositories repositories = byEnvironment.get(environmentName);
        if (repositories == null) {
            return List.of();
        }
        return repositories.repositories().stream()
            .map(SqlRepository::getSource)
            .filter(java.util.Objects::nonNull)
            .map(SqlSource::getSourceRef)
            .filter(java.util.Objects::nonNull)
            .distinct()
            .toList();
    }

    public List<String> sourceRefs(String environmentName) {
        return getRepositories(environmentName).stream()
            .map(SqlRepository::getSource)
            .filter(java.util.Objects::nonNull)
            .map(SqlSource::getSourceRef)
            .filter(java.util.Objects::nonNull)
            .distinct()
            .toList();
    }

    private SqlRepository previouslyServed(String environmentName, String sourceRef) {
        EnvironmentRepositories resident = byEnvironment.get(environmentName);
        if (resident != null) {
            for (SqlRepository repository : resident.repositories()) {
                if (repository.getSource() != null && sourceRef.equals(repository.getSource().getSourceRef())) {
                    return repository;
                }
            }
        }
        return captureRehydrator == null ? null : captureRehydrator.rehydrate(sourceRef);
    }

    private void recordServed(String environmentName, List<SqlRepository> repositories) {
        Map<String, Instant> captured = new java.util.LinkedHashMap<>();
        for (SqlRepository repository : repositories) {
            SqlSource source = repository.getSource();
            if (source == null || source.getSourceRef() == null) {
                continue;
            }
            Instant at = repository.getCaptureContext() != null && repository.getCaptureContext().capturedAt() != null
                ? repository.getCaptureContext().capturedAt()
                : source.getCapturedAt() != null ? source.getCapturedAt() : source.getCreatedOn();
            captured.put(source.getSourceRef(), at);
        }
        servedCaptures.put(environmentName, java.util.Collections.unmodifiableMap(captured));
    }

    public Map<String, Instant> servedCaptures(String environmentName) {
        return environmentName == null ? Map.of() : servedCaptures.getOrDefault(environmentName, Map.of());
    }

    public List<SourceFailure> getFailedSources(String environmentName) {
        return environmentName == null ? List.of() : failedSources.getOrDefault(environmentName, List.of());
    }

    public record SourceFailure(String sourceRef, String reason) { }

    private record SourceResult(SqlRepository repository, SourceFailure failure, RuntimeException cause) {
        static SourceResult loaded(SqlRepository repository) {
            return new SourceResult(repository, null, null);
        }

        static SourceResult failed(String sourceRef, RuntimeException cause) {
            return new SourceResult(null, new SourceFailure(sourceRef, rootMessage(cause)), cause);
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
    }

    public record LoadState(int sourceCount, Instant loadedOn, SqlImportStats importStats, boolean resident) { }

    private static final class EnvironmentRepositories {
        static final EnvironmentRepositories EMPTY =
            new EnvironmentRepositories(List.of(), new CompositeSqlRepository(List.of()), null);
        private static final int MAX_SCOPES = 32;

        private final List<SqlRepository> repositories;
        private final CompositeSqlRepository composite;
        private final Instant loadedOn;
        private final Map<SourceScope, SqlRepository> scoped = new ConcurrentHashMap<>();

        EnvironmentRepositories(List<SqlRepository> repositories, CompositeSqlRepository composite, Instant loadedOn) {
            this.repositories = repositories;
            this.composite = composite;
            this.loadedOn = loadedOn;
        }

        List<SqlRepository> repositories() {
            return repositories;
        }

        CompositeSqlRepository composite() {
            return composite;
        }

        Instant loadedOn() {
            return loadedOn;
        }

        SqlRepository scoped(SourceScope scope) {
            if (scope == null || scope.isEverything()) {
                return composite;
            }
            SqlRepository cached = scoped.get(scope);
            if (cached != null) {
                return cached;
            }
            if (scoped.size() >= MAX_SCOPES) {
                scoped.clear();
            }
            return scoped.computeIfAbsent(scope, s -> new CompositeSqlRepository(
                repositories.stream().filter(repository -> s.includes(repository.sourceRef())).toList()));
        }
    }
}
