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

@Service
public class ArchivedActivity {

    private static final Logger log = LoggerFactory.getLogger(ArchivedActivity.class);

    private final ParquetConfig config;
    private final CaptureArchive archive;
    private final ParquetSink sink;
    private final SqlRepositoryManager repositories;
    private final EnvironmentContext environmentContext;

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

    public Map<String, LocalDateTime> lastTouchedBeforeThisRun() {
        return lastTouchedBeforeThisRun(environmentContext.current().getName());
    }

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

    private List<Path> earlierRunCaptures(String environmentName) {
        Set<String> mine = Set.copyOf(repositories.sourceRefs(environmentName));
        if (mine.isEmpty()) {
            return List.of();
        }

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
            for (CaptureArchive.CaptureRun run : runs.subList(0, runs.size() - 1)) {
                newestOf(placed, source.sourceRef(), run).ifPresent(wanted::add);
            }
        }
        return List.copyOf(wanted);
    }

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
