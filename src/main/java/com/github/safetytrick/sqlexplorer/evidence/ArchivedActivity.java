package com.github.safetytrick.sqlexplorer.evidence;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.github.safetytrick.sqlexplorer.avro.RuntimeMetrics;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.environment.EnvironmentContext;
import com.github.safetytrick.sqlexplorer.evidence.sink.CaptureArchive;
import com.github.safetytrick.sqlexplorer.evidence.sink.CaptureContext;
import com.github.safetytrick.sqlexplorer.evidence.sink.ParquetConfig;
import com.github.safetytrick.sqlexplorer.evidence.sink.ParquetSink;
import com.github.safetytrick.sqlexplorer.evidence.sink.ParquetSqlRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * When a table was last touched by a query the current corpus can no longer see.
 *
 * <p>THE PROBLEM THIS SOLVES. Performance Schema is cleared on reset, so after
 * one the digest table holds only what has run since. A query that ran before
 * the reset and not after is not "unqueried" — it is ABSENT, and nothing in the
 * corpus records that it existed or when it last ran. A table touched only by a
 * quarterly job therefore reads as untouched on a corpus captured a week after a
 * restart, and {@code /unqueried} says so with a straight face: an artifact of
 * the capture presented as a fact about the schema (sqlx-qwn).
 *
 * <p>Measured before this existed: 128 of 4,311 profiles had not run in thirty
 * days, the oldest last seen nearly four months back. All of them vanish at the
 * next reset unless an earlier capture is consulted.
 *
 * <p>WHAT IT UNIONS, AND WHAT IT DOES NOT. The query SET and its last-seen, not
 * the counters. Summing executions across a reset is arithmetically valid — the
 * intervals are disjoint — but it answers a question nobody asked, and it would
 * make what the workbench shows differ from what was imported. This adds
 * knowledge of which tables were used and when; the metrics of the current run
 * are untouched.
 *
 * <p>ONLY EARLIER RUNS ARE READ. The newest run is the corpus, already in heap;
 * re-reading it here would be redundant work and would risk this and the corpus
 * disagreeing about the same rows. With retention on there is at most one
 * capture per run, and a reset is rare, so on a healthy source this reads
 * nothing at all.
 */
@Service
public class ArchivedActivity {

    private static final Logger log = LoggerFactory.getLogger(ArchivedActivity.class);

    private final ParquetConfig config;
    private final CaptureArchive archive;
    private final ParquetSink sink;
    private final SqlRepositoryManager repositories;
    private final EnvironmentContext environmentContext;

    /**
     * One entry per environment, each remembering which capture files produced
     * it so it recomputes when the archive changes.
     *
     * <p>PER ENVIRONMENT, not one slot. Two things follow from the archive
     * directory being global while a corpus is not. First, which run counts as
     * "current" depends on the environment loaded, so a single cached answer
     * would be served to an environment it does not describe — the bug the
     * coverage cache shipped with. Second, a lone slot would be evicted on
     * every switch, and switching back would re-read the archive from disk.
     */
    private record Cached(List<String> captures, Map<String, LocalDateTime> lastTouched) { }

    private final Map<String, Cached> byEnvironment = new java.util.concurrent.ConcurrentHashMap<>();

    public ArchivedActivity(ParquetConfig config, CaptureArchive archive, ParquetSink sink,
                            SqlRepositoryManager repositories, EnvironmentContext environmentContext) {
        this.config = config;
        this.archive = archive;
        this.sink = sink;
        this.repositories = repositories;
        this.environmentContext = environmentContext;
    }

    /** The environment selected on the current request. */
    public Map<String, LocalDateTime> lastTouchedBeforeThisRun() {
        return lastTouchedBeforeThisRun(environmentContext.current().getName());
    }

    /**
     * Table key to the newest time a query touched it in a period this
     * environment's corpus does not cover.
     *
     * <p>Keys are {@code schema.table}, lower-cased — the same shape the schema
     * tree uses, so a caller can look a row up directly. Empty when no source
     * has an earlier run, which is the ordinary case.
     */
    public Map<String, LocalDateTime> lastTouchedBeforeThisRun(String environmentName) {
        List<Path> captures = earlierRunCaptures(environmentName);
        List<String> names = captures.stream().map(path -> String.valueOf(path.getFileName())).toList();
        Cached cached = byEnvironment.get(environmentName);
        if (cached != null && cached.captures().equals(names)) {
            return cached.lastTouched();
        }
        Map<String, LocalDateTime> touched = new HashMap<>();
        for (Path capture : captures) {
            collect(capture, touched);
        }
        Map<String, LocalDateTime> answer = Map.copyOf(touched);
        byEnvironment.put(environmentName, new Cached(names, answer));
        if (!answer.isEmpty()) {
            log.info("Archive remembers {} tables in {} last touched before the current run",
                answer.size(), environmentName);
        }
        return answer;
    }

    /**
     * The newest capture of every run EXCEPT each source's current one.
     *
     * <p>A run's newest capture contains everything the others in it saw, so one
     * file per earlier run is the whole of what the corpus is missing.
     *
     * <p>Restricted to the sources this environment actually loaded. The archive
     * directory holds every environment's captures side by side, and history
     * from a source this environment never reads is not its history.
     */
    private List<Path> earlierRunCaptures(String environmentName) {
        Set<String> mine = Set.copyOf(repositories.sourceRefs(environmentName));
        if (mine.isEmpty()) {
            return List.of();
        }

        // read every footer once, rather than per run
        Map<Path, CaptureContext> placed = new LinkedHashMap<>();
        for (Path capture : ParquetSqlRepository.captures(config.getFilename())) {
            CaptureContext context = CaptureArchive.provenanceOf(capture);
            if (context != null && mine.contains(context.sourceRef())) {
                placed.put(capture, context);
            }
        }

        List<Path> wanted = new ArrayList<>();
        for (CaptureArchive.SourceRuns source : archive.scan().runs()) {
            if (!mine.contains(source.sourceRef())) {
                continue;
            }
            List<CaptureArchive.CaptureRun> runs = source.runs();
            if (runs.size() < 2) {
                continue;
            }
            // every run but the last; the last is the loaded corpus
            for (CaptureArchive.CaptureRun run : runs.subList(0, runs.size() - 1)) {
                newestOf(placed, source.sourceRef(), run).ifPresent(wanted::add);
            }
        }
        return List.copyOf(wanted);
    }

    /**
     * The last capture taken during this run.
     *
     * <p>Selected as the newest {@code capturedAt} among the captures sharing
     * the run's {@code observedFrom}, which is the property actually wanted.
     * Matching the run's {@code observedTo} instead would give the same file
     * today but only because of how {@link CaptureArchive} happens to compute
     * a run's end — a dependency on internals that has no reason to hold.
     */
    private static Optional<Path> newestOf(Map<Path, CaptureContext> placed, String sourceRef,
                                           CaptureArchive.CaptureRun run) {
        if (run.observedFrom() == null) {
            return Optional.empty();
        }
        return placed.entrySet().stream()
            .filter(entry -> sourceRef.equals(entry.getValue().sourceRef())
                && run.observedFrom().equals(entry.getValue().observedFrom())
                && entry.getValue().capturedAt() != null)
            .max(Comparator.comparing(entry -> entry.getValue().capturedAt()))
            .map(Map.Entry::getKey);
    }

    private void collect(Path capture, Map<String, LocalDateTime> into) {
        List<SqlProfile> rows;
        try {
            rows = sink.readAll(capture);
        } catch (Exception e) {
            log.warn("could not read archived capture {}: {}", capture.getFileName(), e.getMessage());
            return;
        }
        for (SqlProfile profile : rows) {
            RuntimeMetrics metrics = profile.getRuntimeMetrics();
            LocalDateTime lastSeen = metrics == null ? null : metrics.getLastSeen();
            if (lastSeen == null || profile.getTables() == null) {
                continue;
            }
            for (TableReference table : profile.getTables()) {
                String key = keyOf(table, profile.getSchemaName());
                if (key != null) {
                    into.merge(key, lastSeen, (a, b) -> b.isAfter(a) ? b : a);
                }
            }
        }
    }

    /**
     * {@code schema.table}, lower-cased.
     *
     * <p>Falls back to the profile's own schema for an unqualified reference,
     * the same way the in-memory index does — a statement that named a table
     * without a schema meant the one it was running in.
     */
    static String keyOf(TableReference table, String defaultSchema) {
        String schema = table.getNormalizedSchemaName() != null ? table.getNormalizedSchemaName()
            : table.getSchemaName() != null ? table.getSchemaName() : defaultSchema;
        String name = table.getNormalizedTableName() != null ? table.getNormalizedTableName()
            : table.getTableName();
        if (schema == null || name == null) {
            return null;
        }
        return (schema + "." + name).toLowerCase(Locale.ROOT);
    }
}
