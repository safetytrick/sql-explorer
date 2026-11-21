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

    public record Pruned(List<String> kept, List<String> deleted) { }

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

    public boolean isEnabled() {
        return enabled;
    }

    CaptureArchive archive() {
        return archive;
    }
}
