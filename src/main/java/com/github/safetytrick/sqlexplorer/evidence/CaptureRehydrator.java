package com.github.safetytrick.sqlexplorer.evidence;

import java.nio.file.Path;
import java.util.List;

import com.github.safetytrick.sqlexplorer.analysis.parser.QueryNormalizer;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.evidence.sink.CaptureArchive;
import com.github.safetytrick.sqlexplorer.evidence.sink.CaptureContext;
import com.github.safetytrick.sqlexplorer.evidence.sink.ParquetConfig;
import com.github.safetytrick.sqlexplorer.evidence.sink.ParquetSink;
import com.github.safetytrick.sqlexplorer.evidence.sink.ParquetSqlRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class CaptureRehydrator {

    private static final Logger log = LoggerFactory.getLogger(CaptureRehydrator.class);

    private final ParquetConfig config;
    private final ParquetSink sink;

    public CaptureRehydrator(ParquetConfig config, ParquetSink sink) {
        this.config = config;
        this.sink = sink;
    }

    public SqlRepository rehydrate(String sourceRef) {
        Path capture = ParquetSqlRepository.latestCaptureOf(config.getFilename(), sourceRef);
        if (capture == null) {
            log.info("no capture for source {}; it has to be imported", sourceRef);
            return null;
        }
        CaptureContext context = CaptureArchive.provenanceOf(capture);
        List<SqlProfile> rows;
        try {
            rows = sink.readAll(capture);
        } catch (Exception e) {
            log.warn("could not read capture {}: {}", capture.getFileName(), e.getMessage());
            return null;
        }
        if (!normalizerMatches(rows)) {
            return null;
        }

        labelSources(rows, context != null && context.sourceRef() != null ? context.sourceRef() : sourceRef);

        log.info("Rehydrated {} from {}: {} profiles", sourceRef, capture.getFileName(), rows.size());
        return new AnalyzedSqlRepository(new CapturedSqlSource(capture, context), statsOf(rows), rows, context);
    }

    public AnalysisReuse reuseFor(List<String> sourceRefs) {
        long start = System.nanoTime();
        AnalysisReuse.Builder index = new AnalysisReuse.Builder();
        int captures = 0;
        for (String sourceRef : sourceRefs) {
            Path capture = sourceRef == null ? null : ParquetSqlRepository.latestCaptureOf(config.getFilename(), sourceRef);
            if (capture == null) {
                continue;
            }
            try {
                index.addAll(sink.readAll(capture));
                captures++;
            } catch (Exception e) {
                log.warn("could not read capture {} for analysis reuse: {}", capture.getFileName(), e.getMessage());
            }
        }
        AnalysisReuse reuse = index.build();
        log.info("Analysis reuse: {} statements from {} capture(s) in {}", reuse.size(), captures,
            java.time.Duration.ofNanos(System.nanoTime() - start));
        return reuse;
    }

    public boolean canRehydrate(String sourceRef) {
        return ParquetSqlRepository.latestCaptureOf(config.getFilename(), sourceRef) != null;
    }

    static void labelSources(List<SqlProfile> rows, String sourceRef) {
        if (sourceRef == null) {
            return;
        }
        for (SqlProfile row : rows) {
            if (row.getSources() == null || row.getSources().isEmpty()) {
                row.setSources(List.of(sourceRef));
            }
        }
    }

    private static boolean normalizerMatches(List<SqlProfile> rows) {
        String expected = "v" + QueryNormalizer.VERSION + ":";
        String stale = rows.stream()
            .map(SqlProfile::getNormalizedDigest)
            .filter(digest -> digest != null && !digest.startsWith(expected))
            .findFirst().orElse(null);
        if (stale != null) {
            log.info("capture was normalized by a different scheme ({} vs {}); importing instead",
                stale.substring(0, Math.min(stale.indexOf(':') + 1, stale.length())), expected);
            return false;
        }
        return true;
    }

    static SqlImportStats statsOf(List<SqlProfile> rows) {
        SqlImportStats.Builder stats = SqlImportStats.builder();
        long distinct = rows.stream().map(SqlProfile::getNormalizedDigest).distinct().count();
        for (SqlProfile row : rows) {
            stats.count();
            if (Boolean.TRUE.equals(row.getTruncated())) {
                stats.truncated();
            }
            if (Boolean.TRUE.equals(row.getAnalysisFailed())) {
                stats.analysisFailed();
            }
            if (Boolean.TRUE.equals(row.getAnalysisSkipped())) {
                stats.analysisSkipped();
            }
            if (Boolean.TRUE.equals(row.getIsDml())) {
                stats.dml();
            }
        }
        for (long i = 0; i < rows.size() - distinct; i++) {
            stats.merged();
        }
        return stats.build();
    }
}
