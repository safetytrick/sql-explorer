package com.github.safetytrick.sqlexplorer.indexes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.regex.Pattern;

import com.github.safetytrick.sqlexplorer.evidence.sink.ParquetConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps the last unused-index report of each environment on disk, so a
 * restart does not forget it (sqlx-1bo.2). Reading one connects to every
 * server in the environment, which is why the page shows the kept report
 * rather than reading on every visit, and a report lost to a restart would
 * cost exactly that read.
 *
 * <p>One JSON file per environment in an {@code unused-indexes/} directory
 * beside the capture archive, like the schema snapshots. JSON rather than the
 * snapshots' Parquet: this is one small nested document, read whole and
 * written whole, with nothing columnar about it.
 *
 * <p>A cache, so a missing, unreadable or other-format file is simply no
 * report: the page offers to read the connections, as it does for an
 * environment never read.
 */
@Service
public class UnusedIndexReportStore {

    /** Bump when {@link UnusedIndexes.Report} changes shape; older files are then ignored. */
    public static final int FORMAT = 1;

    private static final Logger log = LoggerFactory.getLogger(UnusedIndexReportStore.class);
    private static final Pattern UNSAFE = Pattern.compile("[^A-Za-z0-9._-]");

    /** What is written: the report and the format it was written in. */
    record Stored(int format, UnusedIndexes.Report report) {
    }

    private final Path directory;
    private final JsonMapper json;

    @Autowired
    public UnusedIndexReportStore(ParquetConfig config, JsonMapper json) {
        this(besideCaptures(Path.of(config.getFilename()).toAbsolutePath()), json);
    }

    public UnusedIndexReportStore(Path directory, JsonMapper json) {
        this.directory = directory;
        this.json = json;
    }

    private static Path besideCaptures(Path captureBase) {
        Path archive = captureBase.getParent();
        return (archive == null ? captureBase : archive).resolve("unused-indexes");
    }

    public Optional<UnusedIndexes.Report> read(String environment) {
        Path file = fileFor(environment);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            Stored stored = json.readValue(file.toFile(), Stored.class);
            if (stored.format() != FORMAT || stored.report() == null) {
                log.info("Unused-index report {} is format {}, not {}; it will be replaced on the next read",
                    file, stored.format(), FORMAT);
                return Optional.empty();
            }
            return Optional.of(stored.report());
        } catch (RuntimeException e) {
            log.warn("Unused-index report {} could not be read and will be replaced on the next read: {}",
                file, e.toString());
            return Optional.empty();
        }
    }

    /** Written to a sibling and moved into place, so a reader never sees half a report. */
    public void write(UnusedIndexes.Report report) {
        Path file = fileFor(report.environment());
        Path partial = file.resolveSibling(file.getFileName() + ".partial");
        try {
            Files.createDirectories(directory);
            json.writeValue(partial.toFile(), new Stored(FORMAT, report));
            Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            // A report that cannot be kept costs one more read after a restart, nothing more.
            log.warn("Unused-index report {} could not be written: {}", file, e.toString());
            try {
                Files.deleteIfExists(partial);
            } catch (IOException ignored) {
                // the next write overwrites it
            }
        }
    }

    Path fileFor(String environment) {
        return directory.resolve(UNSAFE.matcher(environment).replaceAll("_") + ".json");
    }
}
