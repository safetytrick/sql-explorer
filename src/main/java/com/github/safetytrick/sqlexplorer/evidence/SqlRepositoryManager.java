package com.github.safetytrick.sqlexplorer.evidence;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.environment.EnvironmentRegistry;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlSource;
import com.github.safetytrick.sqlexplorer.schema.SqlSchemaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Holds the loaded SQL repositories per environment. Loading sources for one
 * environment replaces only that environment's repositories; queries resolve
 * the repository for the environment selected on the request.
 */
@Service
public class SqlRepositoryManager {

    private static final Logger log = LoggerFactory.getLogger(SqlRepositoryManager.class);

    private final SqlEvidenceInputService sqlEvidenceInputService;
    private final EnvironmentRegistry environmentRegistry;
    private final CaptureRehydrator captureRehydrator;
    private final Clock clock;

    /**
     * How many environments stay in heap at once. Zero or less disables
     * eviction entirely.
     *
     * <p>Sized from measurement rather than taste: the regression corpus is
     * 32 MB resident for 4,244 profiles, about 7.7 KB each, against a 512 MB
     * heap. Five environments at 10,000 profiles is ~380 MB, which is real
     * pressure; three leaves room for everything else the process does. A
     * deployment with fewer environments than the cap never evicts anything,
     * so the default costs those nothing (sqlx-4ww).
     */
    private final int residentEnvironments;
    private final Map<String, EnvironmentRepositories> byEnvironment = new ConcurrentHashMap<>();

    /**
     * When each environment was last asked for, so the least recently used can
     * be the one dropped. Touched by every read, which is the point.
     */
    private final Map<String, Instant> lastAccess = new ConcurrentHashMap<>();

    /**
     * What an evicted environment was made of, so it can be rebuilt.
     *
     * <p>An entry here means "loaded, but not resident" — which is a different
     * thing from never loaded, and the difference reaches the environments
     * listing through {@link LoadState#resident()}.
     */
    private final Map<String, List<String>> evicted = new ConcurrentHashMap<>();

    /**
     * One rehydration at a time per environment.
     *
     * <p>Without this, a burst of requests against an evicted environment
     * would each read the same capture and rebuild the same indexes, N times
     * over — the thundering-herd shape, and worst exactly when the app is
     * already busy. Not a lock on {@code byEnvironment} itself: rehydration
     * does IO, and holding a ConcurrentHashMap bin across a file read would
     * block unrelated environments.
     */
    private final Map<String, ReentrantLock> rehydrationLocks = new ConcurrentHashMap<>();

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

    public List<SqlRepository> getRepositories(String environmentName) {
        return resident(environmentName).repositories();
    }

    /**
     * The environment's repositories, rebuilding them from its captures if they
     * were evicted.
     *
     * <p>Every read goes through here, which is what makes the access order
     * true and what makes an evicted environment indistinguishable from a
     * resident one to a caller (sqlx-4ww).
     */
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
            // another thread may have finished while this one waited
            loaded = byEnvironment.get(environmentName);
            if (loaded != null) {
                return loaded;
            }
            if (rehydrate(environmentName, sourceRefs)) {
                evicted.remove(environmentName);
                return byEnvironment.getOrDefault(environmentName, EnvironmentRepositories.EMPTY);
            }
            // Refused — a capture is missing, unreadable, or from another
            // normalizer. Nothing to serve, and pretending otherwise would show
            // an empty corpus for an environment that has one.
            log.warn("Environment {} was evicted and cannot be rehydrated; it must be loaded again",
                environmentName);
            return EnvironmentRepositories.EMPTY;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Load state, or {@code null} when nothing has ever been loaded.
     *
     * <p>Deliberately does NOT rehydrate. This feeds the environments listing,
     * which enumerates everything — rehydrating from it would rebuild every
     * evicted environment the moment someone opened a dropdown, which is the
     * opposite of what eviction is for.
     */
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

    /** Load into the default environment (used by the startup loaders). */
    public SqlImportStats loadFromSqlSources(Stream<? extends SqlSource> sources) {
        return loadFromSqlSources(environmentRegistry.getDefaultName(), sources);
    }

    public SqlImportStats loadFromSqlSources(String environmentName, Stream<? extends SqlSource> sources) {
        SqlSchemaRepository schema = environmentRegistry.get(environmentName).schemaRepository();

        List<SqlRepository> repositories = sources.parallel().map(source -> {
            log.info("Loading source into environment {}: {}", environmentName, source);

            return sqlEvidenceInputService.fromPerformanceSchema(source, source.getSql(), schema);
        }).toList();

        byEnvironment.put(environmentName, new EnvironmentRepositories(List.copyOf(repositories),
            new CompositeSqlRepository(repositories), clock.instant()));
        lastAccess.put(environmentName, clock.instant());
        evicted.remove(environmentName);
        evictDownTo(environmentName);

        SqlImportStats stats = getSqlRepository(environmentName).getImportStats();
        log.info("Import stats for environment {}: {}", environmentName, stats);
        return stats;
    }

    /**
     * Rebuild an environment from its sources' latest captures rather than
     * importing them again.
     *
     * <p>For coming back to an environment that was dropped from heap: the
     * captures were written by this process from a live read, so they are what
     * heap held, and reading them costs a fraction of a re-import. Startup does
     * NOT use this — Performance Schema accumulates, so a live read is always
     * at least as complete as an earlier capture (sqlx-4ww).
     *
     * <p>All or nothing. A source whose capture is missing, unreadable, or
     * written by a different normalizer cannot be rebuilt, and a partially
     * rehydrated environment would be a corpus missing one server with nothing
     * saying so. The caller's answer to false is to load the environment
     * properly.
     *
     * @return whether every source was rebuilt
     */
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
        byEnvironment.put(environmentName, new EnvironmentRepositories(List.copyOf(repositories),
            new CompositeSqlRepository(repositories), clock.instant()));
        lastAccess.put(environmentName, clock.instant());
        evictDownTo(environmentName);
        log.info("Rehydrated environment {} from {} capture(s)", environmentName, repositories.size());
        return true;
    }

    /**
     * Drop least-recently-used environments until the cap is met, never
     * touching {@code keep}.
     *
     * <p>{@code keep} is whatever was just loaded or asked for, so the
     * environment someone is actually working in is never the one dropped —
     * evicting it and rebuilding it on their next click would be the
     * pathological case.
     *
     * <p>ONLY WHAT CAN COME BACK IS DROPPED. An environment whose sources have
     * no usable capture is skipped however old it is: evicting it would trade
     * bounded memory for a corpus that can only return by re-importing, which
     * needs the analysed server to be reachable and it may not be. That means
     * the cap is a target rather than a guarantee, and a process holding more
     * than the cap is holding environments it could not safely release.
     */
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

    /** Whether every source of this environment could be rebuilt from a capture. */
    private boolean canEvict(String environmentName) {
        List<String> sourceRefs = residentSourceRefs(environmentName);
        return !sourceRefs.isEmpty() && sourceRefs.stream().allMatch(captureRehydrator::canRehydrate);
    }

    /**
     * Source refs read WITHOUT recording an access.
     *
     * <p>{@link #sourceRefs} goes through {@link #resident}, which touches the
     * access time — correct for a caller, and wrong here. Choosing a victim
     * would otherwise refresh every candidate it examined and then pick the
     * least recently used from the timestamps it had just overwritten, making
     * the choice depend on map iteration order rather than on use. Caught by
     * the test that asserts the least recently used environment is the one
     * dropped (sqlx-4ww).
     */
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

    /** The source refs currently loaded into an environment, for rehydrating it later. */
    public List<String> sourceRefs(String environmentName) {
        return getRepositories(environmentName).stream()
            .map(SqlRepository::getSource)
            .filter(java.util.Objects::nonNull)
            .map(SqlSource::getSourceRef)
            .filter(java.util.Objects::nonNull)
            .distinct()
            .toList();
    }

    /**
     * @param resident whether the corpus is in heap. False means loaded but
     *                 dropped under memory pressure — it comes back on the next
     *                 read, so it is not the same as unloaded, and the listing
     *                 must not say it is
     */
    public record LoadState(int sourceCount, Instant loadedOn, SqlImportStats importStats, boolean resident) { }

    private record EnvironmentRepositories(List<SqlRepository> repositories, CompositeSqlRepository composite,
                                           Instant loadedOn) {
        static final EnvironmentRepositories EMPTY =
            new EnvironmentRepositories(List.of(), new CompositeSqlRepository(List.of()), null);
    }
}
