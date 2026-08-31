package com.github.safetytrick.sqlexplorer.evidence.sink;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import org.apache.avro.Schema;
import org.apache.avro.specific.SpecificRecord;
import org.apache.hadoop.conf.Configuration;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.parquet.avro.AvroParquetWriter;
import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.hadoop.ParquetFileWriter;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.io.LocalInputFile;
import org.apache.parquet.io.LocalOutputFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ParquetSink {

    private static final Logger log = LoggerFactory.getLogger(ParquetSink.class);

    private final ParquetConfig config;

    public ParquetSink(ParquetConfig config) {
        this.config = config;
    }

    /**
     * Write one capture: open, write every row, close.
     *
     * <p>ONE WRITER PER CALL, and that is the whole fix for sqlx-fx2. This used
     * to hold a single lazily-built writer for the life of the process, so the
     * first import owned the file and every later one was appended to it —
     * across sources and across environments. Proven on a running instance:
     * loading a second environment put 4,366 rows in one capture, 4,244 from
     * {@code primary} and 122 from {@code explorer}, under a footer claiming
     * {@code sourceRef=primary, rowCount=4244}.
     *
     * <p>Closing here rather than at {@code @PreDestroy} fixes a second problem
     * the old shape had. Parquet buffers its row group until close, so a
     * process that did not shut down cleanly left a 4-byte file holding nothing
     * — the whole capture lost. Nine such stubs accumulated during development.
     * A capture is now readable the moment its import finishes.
     *
     * @return where it was written, or null when nothing was
     */
    public Path write(CaptureContext capture, Iterable<SqlProfile> profiles) {
        Path file = fileFor(config.getFilename(), capture);
        try (ParquetWriter<Object> writer = writerFor(file, capture)) {
            for (SqlProfile profile : profiles) {
                try {
                    writer.write(profile);
                } catch (IOException | RuntimeException e) {
                    log.info("failed to write individual sql: {}", profile.getId());
                }
            }
        } catch (IOException e) {
            log.warn("could not write capture to {}: {}", file, e.getMessage());
            return null;
        }
        return file;
    }

    private static ParquetWriter<Object> writerFor(Path file, CaptureContext capture) throws IOException {
        return AvroParquetWriter.builder(new LocalOutputFile(file))
            .withSchema(SqlProfile.getClassSchema())
            .withDictionaryEncoding(true)
            .withConf(new Configuration(false))
            .withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
            // absent rather than empty when nothing is known: a reader must be
            // able to tell "no provenance recorded" from "recorded as nothing",
            // which is the distinction sqlx-sy5 turns on
            .withExtraMetaData(capture == null ? Map.of() : capture.asMetadata())
            .build();
    }

    /**
     * Where a capture is written: the configured path with the source and the
     * capture instant folded into the stem.
     *
     * <p>THE SOURCE IS IN THE NAME because it is the unit a capture describes.
     * An environment can read several servers, and two servers' statements
     * belong together no more than two environments' do — sqlx-sy5 already
     * argues gap detection is per datasource for exactly this reason, and
     * mixing them in one file puts the interleaving below the level the archive
     * reader can see.
     *
     * <p>The stamp is ordered lexicographically as well as chronologically, so
     * within a source the newest capture is the last name in a sorted listing
     * and finding it costs no parsing.
     *
     * <p>Falls back to the plain configured name when there is no capture
     * instant, which keeps a context-less write working and readable.
     */
    static Path fileFor(String configuredPath, CaptureContext context) {
        Path base = Path.of(configuredPath);
        Path fileName = base.getFileName();
        // a root path has no file name to stamp; nothing sensible to derive
        if (context == null || context.capturedAt() == null || fileName == null) {
            return base;
        }
        String name = fileName.toString();
        int dot = name.lastIndexOf('.');
        String stem = dot < 0 ? name : name.substring(0, dot);
        String extension = dot < 0 ? "" : name.substring(dot);
        String stamp = STAMP.format(context.capturedAt());
        Path parent = base.getParent();
        Path stamped = Path.of(stem + "-" + fileToken(context.sourceRef()) + stamp + extension);
        return parent == null ? stamped : parent.resolve(stamped);
    }

    /**
     * A source ref, made safe for a file name, or empty when there is none.
     *
     * <p>Two different refs must never produce the same token — that would
     * re-merge their captures and recreate the bug this exists to fix. Refs are
     * usually already safe ({@code primary}, {@code explorer}) and pass through
     * unchanged; anything that had to be rewritten carries a short digest of
     * the original so it stays distinct. {@link com.github.safetytrick.sqlexplorer.evidence.sink.ParquetSqlSource}
     * uses a file path as its ref, which is why this is not merely defensive.
     */
    static String fileToken(String sourceRef) {
        if (sourceRef == null || sourceRef.isBlank()) {
            return "";
        }
        String safe = sourceRef.replaceAll("[^A-Za-z0-9_]", "-");
        if (!safe.equals(sourceRef) || safe.length() > 40) {
            safe = safe.substring(0, Math.min(safe.length(), 32))
                + "-" + Integer.toHexString(sourceRef.hashCode() & 0xffffff);
        }
        return safe + "-";
    }

    private static final DateTimeFormatter STAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    /**
     * Any reader of a profile file needs this. The reader refuses
     * {@code @java-class} stringables unless they are named as trusted, and
     * the profile schema is full of them: every latency on
     * {@code RuntimeMetrics} and {@code MergedChild.averageTime} is a
     * BigDecimal. Without it the read fails with "Forbidden
     * java.math.BigDecimal" — which nothing had ever hit, because nothing
     * had ever successfully read this file back.
     */
    static PlainParquetConfiguration readerConfiguration() {
        return new PlainParquetConfiguration(
            Map.of("parquet.avro.serializable.classes", BigDecimal.class.getName()));
    }

    /** Read the profiles back. A capture is sealed when {@link #write} returns. */
    public List<SqlProfile> readAll(Path parquetFile) throws IOException {
        List<SqlProfile> profiles = new ArrayList<>();
        try (ParquetReader<SqlProfile> parquetReader = AvroParquetReader.<SqlProfile>builder(
                new LocalInputFile(parquetFile), readerConfiguration())
            .build()) {

            SqlProfile profile = parquetReader.read();
            while (profile != null) {
                profiles.add(profile);
                profile = parquetReader.read();
            }
        }
        return profiles;
    }

    public void writeAvroToFile(Path file, Schema schema, Iterable<? extends SpecificRecord> records) throws IOException {
        AvroParquetWriter.Builder<Object> parquetWriter =
            AvroParquetWriter.builder(new LocalOutputFile(file));


        try (ParquetWriter<Object> objectWriter = parquetWriter.withSchema(schema)
            .withDictionaryEncoding(true)
            .withConf(
                new Configuration(false)
            )
            .withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
            .build()) {

            for (SpecificRecord record : records) {
                try {
                    objectWriter.write(record);
                } catch (RuntimeException e) {
                    log.info("failed to write: {}", record);
                }
            }
        }

    }

}
