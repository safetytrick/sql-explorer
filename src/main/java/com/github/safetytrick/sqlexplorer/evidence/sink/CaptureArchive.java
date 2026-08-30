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

/**
 * The older captures, read for the one question they can answer that the
 * corpus cannot: was there a window in which traffic happened and nothing
 * recorded it?
 *
 * <p>{@link ParquetSqlRepository} deliberately reads only the newest capture,
 * because Performance Schema accumulates and unioning overlapping captures
 * would inflate every count. This reads all of them, but only their
 * <em>footers</em> — no rows are scanned, so the cost is one seek per file
 * rather than a second corpus in heap.
 *
 * <p>THE INFERENCE. Performance Schema accumulates until the server restarts,
 * so capture N+1 of one source normally still contains everything capture N
 * saw and the two windows overlap. When capture N+1's earliest
 * {@code firstSeen} is <em>after</em> capture N's latest {@code lastSeen}, the
 * digest table was reset in between — a restart, or a TRUNCATE — and the
 * interval between those two timestamps went unobserved. sql-explorer cannot
 * know why it reset, only that it did, so "unobserved between X and Y" is the
 * whole claim (sqlx-sy5).
 *
 * <p>PER SOURCE, NOT PER ENVIRONMENT. Each source is its own sampling stream.
 * Two sources in one environment may read different servers, or the same
 * server on different cadences; interleaving them would invent gaps that are
 * not there and hide ones that are.
 *
 * <p>TWO CLOCKS, NEVER MIXED. Captures are ordered by {@code capturedAt}, an
 * {@code Instant} — when sql-explorer read the rows. The gap interval is
 * measured between {@code observedTo} and {@code observedFrom}, which are
 * zone-less {@code LocalDateTime} because that is what Performance Schema
 * reports. Comparing across the two families would be wrong by the source
 * server's offset, so nothing here does.
 */
@Service
public class CaptureArchive {

    private static final Logger log = LoggerFactory.getLogger(CaptureArchive.class);

    private final ParquetConfig config;

    public CaptureArchive(ParquetConfig config) {
        this.config = config;
    }

    /**
     * An interval nothing observed, between two captures of one source.
     *
     * @param sourceRef         the sampling stream this gap belongs to
     * @param earlierCapturedAt when the capture before the reset was read
     * @param laterCapturedAt   when the capture after it was read
     * @param unobservedFrom    the last statement time the earlier capture saw
     * @param unobservedTo      the first statement time the later capture saw
     */
    public record CaptureGap(
        String sourceRef,
        Instant earlierCapturedAt,
        Instant laterCapturedAt,
        LocalDateTime unobservedFrom,
        LocalDateTime unobservedTo) {
    }

    /**
     * What the archive says, and what it could not say.
     *
     * @param gaps        every unobserved interval found, source by source
     * @param unorderable captures that could not take part: no provenance at
     *                    all, no capture instant, or no source to attribute
     *                    them to. Reported rather than dropped, because a
     *                    capture silently sorted first or last either invents
     *                    a gap or hides one
     */
    public record ArchiveReport(List<CaptureGap> gaps, List<String> unorderable) {
        public static final ArchiveReport EMPTY = new ArchiveReport(List.of(), List.of());
    }

    /** Read every capture footer on disk and report what the sequence shows. */
    public ArchiveReport scan() {
        Map<Path, CaptureContext> read = new LinkedHashMap<>();
        for (Path capture : ParquetSqlRepository.captures(config.getFilename())) {
            read.put(capture, provenanceOf(capture));
        }
        return report(read);
    }

    /**
     * Provenance recorded in a file's footer, or null when it carries none.
     *
     * <p>A file that cannot be opened is treated the same as one with no
     * provenance: unknown. Failing the whole scan because one archived file is
     * truncated would lose the answer for every other capture.
     */
    static CaptureContext provenanceOf(Path capture) {
        try (ParquetFileReader reader = ParquetFileReader.open(new LocalInputFile(capture))) {
            return CaptureContext.fromMetadata(reader.getFooter().getFileMetaData().getKeyValueMetaData());
        } catch (IOException | RuntimeException e) {
            log.warn("could not read provenance from {}: {}", capture, e.getMessage());
            return null;
        }
    }

    /**
     * The pure half: given what each file said about itself, what does the
     * sequence show? Split out from the IO so the arithmetic is testable
     * without writing Parquet files.
     */
    static ArchiveReport report(Map<Path, CaptureContext> captures) {
        List<String> unorderable = new ArrayList<>();
        Map<String, List<CaptureContext>> bySource = new LinkedHashMap<>();

        captures.forEach((file, context) -> {
            // Ordered by the recorded instant, never by file name: the legacy
            // un-stamped name carries no instant at all, and a capture placed
            // by guesswork is the one way to report a gap that never happened.
            if (context == null || context.capturedAt() == null || context.sourceRef() == null) {
                unorderable.add(String.valueOf(file.getFileName()));
                return;
            }
            bySource.computeIfAbsent(context.sourceRef(), key -> new ArrayList<>()).add(context);
        });

        List<CaptureGap> gaps = new ArrayList<>();
        bySource.forEach((sourceRef, samples) -> gaps.addAll(gapsIn(sourceRef, samples)));
        return new ArchiveReport(List.copyOf(gaps), List.copyOf(unorderable));
    }

    private static List<CaptureGap> gapsIn(String sourceRef, Collection<CaptureContext> samples) {
        List<CaptureContext> ordered = samples.stream()
            .sorted(Comparator.comparing(CaptureContext::capturedAt))
            .toList();

        List<CaptureGap> gaps = new ArrayList<>();
        for (int i = 1; i < ordered.size(); i++) {
            CaptureContext earlier = ordered.get(i - 1);
            CaptureContext later = ordered.get(i);
            // An empty capture states no window, so this pair proves nothing.
            // Reaching past it to compare i-1 with i+1 would find more gaps at
            // the cost of inventing them; silence is the honest answer.
            if (earlier.observedTo() == null || later.observedFrom() == null) {
                continue;
            }
            if (later.observedFrom().isAfter(earlier.observedTo())) {
                gaps.add(new CaptureGap(sourceRef,
                    earlier.capturedAt(), later.capturedAt(),
                    earlier.observedTo(), later.observedFrom()));
            }
        }
        return gaps;
    }
}
