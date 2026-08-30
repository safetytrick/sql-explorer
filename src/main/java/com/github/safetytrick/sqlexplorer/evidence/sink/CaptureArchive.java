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
     * @param failed      captures with no readable footer that are not the one
     *                     being written — a shutdown that did not close the
     *                     writer, most often. Held apart from
     *                     {@code unorderable} because they cannot hide a gap:
     *                     an unreadable file states no window, so the sealed
     *                     captures on either side are still compared directly
     *                     with each other and any reset between them still
     *                     shows. What is lost is that sample, not the answer
     * @param inProgress  the capture currently being written, if there is one.
     *                    Held apart from {@code unorderable} deliberately: a
     *                    running sql-explorer <em>always</em> has one, since
     *                    Parquet writes no footer until close, and counting it
     *                    as doubt would leave the gap signal permanently
     *                    uncertain during normal operation. Its rows are the
     *                    loaded corpus, so it hides no window
     */
    public record ArchiveReport(List<CaptureGap> gaps, List<String> unorderable,
                                List<String> failed, String inProgress) {
        public static final ArchiveReport EMPTY =
            new ArchiveReport(List.of(), List.of(), List.of(), null);
    }

    /**
     * One archived file and what it turned out to be.
     *
     * @param readable whether it has a footer at all. Distinct from carrying
     *                 no provenance: a sealed legacy file is real data that
     *                 simply cannot be placed, while a footerless one is
     *                 either still being written or damaged
     */
    record ArchivedCapture(Path file, boolean readable, CaptureContext context) {
    }

    /**
     * Read every capture footer on disk and report what the sequence shows.
     *
     * <p>The un-stamped configured name is included when it exists. It is not
     * a capture by the naming rule — {@link ParquetSqlRepository#captures}
     * will not list it — but on a deployment that ran the single-file layout
     * it holds the whole pre-migration history, and dropping it silently would
     * discard the only record of the earliest window. Written before
     * provenance existed it carries none, so it lands in {@code unorderable},
     * which is the honest place for it rather than an omission.
     */
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

        // The newest stamped name is what a running import is writing to. If it
        // has no footer yet that is the normal state, not damage.
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
    static ArchiveReport report(List<ArchivedCapture> captures, String inProgress) {
        List<String> unorderable = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        Map<String, List<CaptureContext>> bySource = new LinkedHashMap<>();

        for (ArchivedCapture capture : captures) {
            String name = String.valueOf(capture.file().getFileName());
            // No footer at all: the capture did not complete. It states no
            // window, so it cannot hide a reset — the sealed captures on either
            // side are still compared with each other. A lost sample, not doubt.
            if (!capture.readable()) {
                failed.add(name);
                continue;
            }
            CaptureContext context = capture.context();
            // Readable but unplaceable is different, and IS doubt: it holds
            // rows covering some window at some unknown point in the sequence,
            // and a reset on either side of it would not show. Ordered by the
            // recorded instant, never by file name — a capture placed by
            // guesswork is the one way to report a gap that never happened.
            if (context == null || context.capturedAt() == null || context.sourceRef() == null) {
                unorderable.add(name);
                continue;
            }
            bySource.computeIfAbsent(context.sourceRef(), key -> new ArrayList<>()).add(context);
        }

        List<CaptureGap> gaps = new ArrayList<>();
        bySource.forEach((sourceRef, samples) -> gaps.addAll(gapsIn(sourceRef, samples)));
        return new ArchiveReport(List.copyOf(gaps), List.copyOf(unorderable), List.copyOf(failed), inProgress);
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
