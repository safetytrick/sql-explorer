package com.github.safetytrick.sqlexplorer.schema;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.github.safetytrick.sqlexplorer.avro.ColumnReference;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.evidence.sink.ParquetConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Persists one {@link SchemaSnapshot} per cluster, as JSON in a
 * {@code schema-snapshots/<environment>/} directory beside the capture
 * archive.
 *
 * <p>A snapshot is a cache, so a missing, unreadable or older-format file is
 * simply no snapshot: the cluster is read in full and a new one is written.
 * That is also why JSON rather than Parquet — nothing queries these, and a
 * person debugging a schema can read one.
 */
@Service
public class SchemaSnapshotStore {

    /** Bump when {@link SchemaSnapshot} or what it holds changes shape; older files are then ignored. */
    public static final int FORMAT = 1;

    private static final Logger log = LoggerFactory.getLogger(SchemaSnapshotStore.class);
    private static final Pattern UNSAFE = Pattern.compile("[^A-Za-z0-9._-]");

    private final Path directory;
    private final JsonMapper json = JsonMapper.builder()
        .addMixIn(TableReference.class, AvroRecord.class)
        .addMixIn(ColumnReference.class, AvroRecord.class)
        .build();

    @Autowired
    public SchemaSnapshotStore(ParquetConfig config) {
        this(besideCaptures(Path.of(config.getFilename()).toAbsolutePath()));
    }

    private static Path besideCaptures(Path captureBase) {
        Path archive = captureBase.getParent();
        return (archive == null ? captureBase : archive).resolve("schema-snapshots");
    }

    public SchemaSnapshotStore(Path directory) {
        this.directory = directory;
    }

    public Optional<SchemaSnapshot> read(SchemaSnapshot.Key key) {
        Path file = fileFor(key);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            SchemaSnapshot snapshot = json.readValue(file.toFile(), SchemaSnapshot.class);
            if (snapshot.format() != FORMAT) {
                log.info("Schema snapshot {} is format {}, not {}; it will be replaced", file, snapshot.format(), FORMAT);
                return Optional.empty();
            }
            return Optional.of(snapshot);
        } catch (RuntimeException e) {
            log.warn("Schema snapshot {} could not be read and will be replaced: {}", file, e.toString());
            return Optional.empty();
        }
    }

    /** Written to a sibling and moved into place, so a reader never sees half a snapshot. */
    public void write(SchemaSnapshot snapshot) {
        Path file = fileFor(new SchemaSnapshot.Key(snapshot.environment(), snapshot.cluster()));
        try {
            Path cluster = file.getParent();
            if (cluster != null) {
                Files.createDirectories(cluster);
            }
            Path partial = file.resolveSibling(file.getFileName() + ".partial");
            json.writeValue(partial.toFile(), snapshot);
            Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            // A snapshot that cannot be written costs the next start a full read, nothing more.
            log.warn("Schema snapshot {} could not be written: {}", file, e.toString());
        }
    }

    Path fileFor(SchemaSnapshot.Key key) {
        return directory.resolve(safe(key.environment())).resolve(safe(key.cluster()) + ".json");
    }

    private static String safe(String name) {
        return UNSAFE.matcher(name).replaceAll("_");
    }

    /** Avro records carry their own schema as a property; the fields are all a snapshot needs. */
    @JsonIgnoreProperties(value = {"schema", "specificData"}, ignoreUnknown = true)
    private abstract static class AvroRecord { }
}
