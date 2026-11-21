package com.github.safetytrick.sqlexplorer.evidence.sink;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.io.LocalInputFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class CaptureArchive {

    private static final Logger log = LoggerFactory.getLogger(CaptureArchive.class);

    private final ParquetConfig config;

    public CaptureArchive(ParquetConfig config) {
        this.config = config;
    }

    public record CaptureGap(
        String sourceRef,
        Instant earlierCapturedAt,
        Instant laterCapturedAt,
        LocalDateTime unobservedFrom,
        LocalDateTime unobservedTo) {
    }

    public record ArchiveReport(List<CaptureGap> gaps, List<SourceRuns> runs, List<String> unorderable,
                                List<String> failed, String inProgress) {
        public static final ArchiveReport EMPTY =
            new ArchiveReport(List.of(), List.of(), List.of(), List.of(), null);

        public List<CaptureRun> runsOf(String sourceRef) {
            return runs.stream()
                .filter(entry -> entry.sourceRef().equals(sourceRef))
                .findFirst().map(SourceRuns::runs).orElse(List.of());
        }
    }

    record ArchivedCapture(Path file, boolean readable, CaptureContext context) {
    }

    public ArchiveReport scan() {
        List<ArchivedCapture> read = new ArrayList<>();
        Path legacy = Path.of(config.getFilename());
        if (legacy.toFile().isFile()) {
            read.add(readCapture(legacy));
        }
        List<Path> stamped = ParquetSqlRepository.captures(config.getFilename());
        for (Path capture : stamped) {
            read.add(readCapture(capture));
        }

        String inProgress = null;
        if (!stamped.isEmpty()) {
            Path newest = stamped.get(stamped.size() - 1);
            ArchivedCapture last = read.get(read.size() - 1);
            if (last.file().equals(newest) && !last.readable()) {
                inProgress = String.valueOf(newest.getFileName());
                read.remove(read.size() - 1);
            }
        }
        return report(read, inProgress);
    }

    private static ArchivedCapture readCapture(Path file) {
        try (ParquetFileReader reader = ParquetFileReader.open(new LocalInputFile(file))) {
            return new ArchivedCapture(file, true,
                CaptureContext.fromMetadata(reader.getFooter().getFileMetaData().getKeyValueMetaData()));
        } catch (IOException | RuntimeException e) {
            log.debug("no footer on {}: {}", file, e.getMessage());
            return new ArchivedCapture(file, false, null);
        }
    }

    public static CaptureContext provenanceOf(Path capture) {
        try (ParquetFileReader reader = ParquetFileReader.open(new LocalInputFile(capture))) {
            return CaptureContext.fromMetadata(reader.getFooter().getFileMetaData().getKeyValueMetaData());
        } catch (IOException | RuntimeException e) {
            log.warn("could not read provenance from {}: {}", capture, e.getMessage());
            return null;
        }
    }

    static ArchiveReport report(List<ArchivedCapture> captures, String inProgress) {
        List<String> unorderable = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        Map<String, List<CaptureContext>> bySource = new LinkedHashMap<>();

        for (ArchivedCapture capture : captures) {
            String name = String.valueOf(capture.file().getFileName());
            if (!capture.readable()) {
                failed.add(name);
                continue;
            }
            CaptureContext context = capture.context();
            if (context == null || context.capturedAt() == null || context.sourceRef() == null) {
                unorderable.add(name);
                continue;
            }
            bySource.computeIfAbsent(context.sourceRef(), key -> new ArrayList<>()).add(context);
        }

        List<CaptureGap> gaps = new ArrayList<>();
        List<SourceRuns> runs = new ArrayList<>();
        bySource.forEach((sourceRef, samples) -> {
            Segments segments = segmentsIn(sourceRef, samples);
            gaps.addAll(segments.gaps());
            runs.add(new SourceRuns(sourceRef, segments.runs()));
        });
        return new ArchiveReport(List.copyOf(gaps), List.copyOf(runs),
            List.copyOf(unorderable), List.copyOf(failed), inProgress);
    }

    public record CaptureRun(LocalDateTime observedFrom, LocalDateTime observedTo, int captures) {
    }

    public record SourceRuns(String sourceRef, List<CaptureRun> runs) {
    }

    private record Segments(List<CaptureGap> gaps, List<CaptureRun> runs) {
    }

    /**
     * THE RESET SIGNAL IS {@code observedFrom} ADVANCING. Uptime going
     * backwards proves a restart but is not necessary: performance_schema can
     * be truncated without one. And capacity is not eviction — at
     * {@code digests_size} the server stops taking NEW digests rather than
     * dropping old ones, so a full table does not move {@code observedFrom}
     * (sqlx-65m).
     */
    private static Segments segmentsIn(String sourceRef, Collection<CaptureContext> samples) {
        List<CaptureContext> ordered = samples.stream()
            .sorted(Comparator.comparing(CaptureContext::capturedAt))
            .toList();

        List<CaptureGap> gaps = new ArrayList<>();
        List<CaptureRun> runs = new ArrayList<>();
        int runStart = 0;

        for (int i = 1; i < ordered.size(); i++) {
            CaptureContext earlier = ordered.get(i - 1);
            CaptureContext later = ordered.get(i);
            if (earlier.observedFrom() == null || later.observedFrom() == null) {
                continue;
            }
            if (!later.observedFrom().isAfter(earlier.observedFrom())) {
                continue;
            }
            runs.add(runOf(ordered, runStart, i));
            runStart = i;
            if (earlier.observedTo() != null && later.observedFrom().isAfter(earlier.observedTo())) {
                gaps.add(new CaptureGap(sourceRef,
                    earlier.capturedAt(), later.capturedAt(),
                    earlier.observedTo(), later.observedFrom()));
            }
        }
        if (!ordered.isEmpty()) {
            runs.add(runOf(ordered, runStart, ordered.size()));
        }
        return new Segments(gaps, runs);
    }

    private static CaptureRun runOf(List<CaptureContext> ordered, int from, int to) {
        CaptureContext newest = ordered.get(to - 1);
        return new CaptureRun(newest.observedFrom(), newest.observedTo(), to - from);
    }
}
