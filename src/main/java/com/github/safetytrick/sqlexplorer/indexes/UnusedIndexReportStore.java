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

@Service
public class UnusedIndexReportStore {

    public static final int FORMAT = 1;

    private static final Logger log = LoggerFactory.getLogger(UnusedIndexReportStore.class);
    private static final Pattern UNSAFE = Pattern.compile("[^A-Za-z0-9._-]");

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

    public void write(UnusedIndexes.Report report) {
        Path file = fileFor(report.environment());
        Path partial = file.resolveSibling(file.getFileName() + ".partial");
        try {
            Files.createDirectories(directory);
            json.writeValue(partial.toFile(), new Stored(FORMAT, report));
            Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            log.warn("Unused-index report {} could not be written: {}", file, e.toString());
            try {
                Files.deleteIfExists(partial);
            } catch (IOException ignored) {
            }
        }
    }

    Path fileFor(String environment) {
        return directory.resolve(UNSAFE.matcher(environment).replaceAll("_") + ".json");
    }
}
