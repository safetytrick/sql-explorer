package com.github.safetytrick.sqlexplorer.evidence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.github.safetytrick.sqlexplorer.evidence.sink.CaptureArchive;
import com.github.safetytrick.sqlexplorer.evidence.sink.CaptureContext;
import com.github.safetytrick.sqlexplorer.evidence.sink.ParquetConfig;
import com.github.safetytrick.sqlexplorer.evidence.sink.ParquetSqlRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Prunes captures that carry nothing another capture does not.
 *
 * <p>WHY THIS EXISTS NOW. Captures accumulated one per process start, so the
 * question of retention was theoretical. On a schedule it stops being: at about
 * 4.2 MB a capture, hourly is roughly 100 MB a day, and a cron without a
 * retention answer is a disk-filling bug with a timer on it (sqlx-ijz).
 *
 * <p>THE RULE IS NOT "KEEP THE LAST N", and that distinction is the whole
 * design. Performance Schema accumulates until it is reset, so within a run of
 * captures the newest strictly contains the others — they are genuinely
 * redundant. Across a reset it contains nothing of the earlier ones: the
 * counters restarted, so the capture before the boundary is the only record
 * that a period existed at all, and of which queries ran in it. A count-based
 * or age-based policy would eventually delete exactly those, which is the
 * history sqlx-qwn wants — the last-seen of queries that run rarely.
 *
 * <p>So: keep the newest capture of every run, delete the rest. That prunes
 * everything a schedule produces while a source is running normally, and never
 * touches the captures either side of a reset.
 *
 * <p>Nothing is deleted unless retention is switched on, and a capture that
 * cannot be placed in a run is always kept: the archive already reports those
 * separately, and deleting something it could not read would be deleting
 * evidence sight unseen.
 */
@Service
public class CaptureRetention {

    private static final Logger log = LoggerFactory.getLogger(CaptureRetention.class);

    private final ParquetConfig config;
    private final CaptureArchive archive;
    private final boolean enabled;

    public CaptureRetention(ParquetConfig config, CaptureArchive archive,
                            @Value("${sql-explorer.prune-redundant-captures:false}") boolean enabled) {
        this.config = config;
        this.archive = archive;
        this.enabled = enabled;
    }

    /**
     * @param kept    captures that are the newest of a run, or unplaceable
     * @param deleted captures another capture already contained
     */
    public record Pruned(List<String> kept, List<String> deleted) { }

    /** Delete redundant captures, or report what would go when switched off. */
    public Pruned prune() {
        Map<Path, CaptureContext> placed = new LinkedHashMap<>();
        for (Path capture : ParquetSqlRepository.captures(config.getFilename())) {
            placed.put(capture, CaptureArchive.provenanceOf(capture));
        }

        List<String> kept = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        for (Map.Entry<Path, CaptureContext> entry : placed.entrySet()) {
            Path file = entry.getKey();
            String name = String.valueOf(file.getFileName());
            if (redundant(entry.getValue(), placed.values())) {
                if (!enabled) {
                    deleted.add(name);
                    continue;
                }
                try {
                    Files.delete(file);
                    deleted.add(name);
                } catch (IOException e) {
                    log.warn("could not prune {}: {}", name, e.getMessage());
                    kept.add(name);
                }
            } else {
                kept.add(name);
            }
        }
        if (!deleted.isEmpty()) {
            log.info("{} {} redundant capture(s); {} kept",
                enabled ? "Pruned" : "Would prune", deleted.size(), kept.size());
        }
        return new Pruned(List.copyOf(kept), List.copyOf(deleted));
    }

    /**
     * Whether some other capture of the same source contains this one.
     *
     * <p>True only when a LATER capture shares this one's {@code observedFrom}:
     * same run, and Performance Schema accumulated everything this saw into it.
     * A later capture with a different start is on the other side of a reset
     * and contains nothing of this one.
     */
    private static boolean redundant(CaptureContext candidate, Iterable<CaptureContext> all) {
        if (candidate == null || candidate.capturedAt() == null
            || candidate.sourceRef() == null || candidate.observedFrom() == null) {
            return false;
        }
        for (CaptureContext other : all) {
            if (other == null || other == candidate || other.capturedAt() == null) {
                continue;
            }
            if (candidate.sourceRef().equals(other.sourceRef())
                && candidate.observedFrom().equals(other.observedFrom())
                && other.capturedAt().isAfter(candidate.capturedAt())) {
                return true;
            }
        }
        return false;
    }

    /** Whether pruning actually deletes, or only reports what it would delete. */
    public boolean isEnabled() {
        return enabled;
    }

    /** Exposed so the archive's own view and this one cannot drift apart. */
    CaptureArchive archive() {
        return archive;
    }
}
