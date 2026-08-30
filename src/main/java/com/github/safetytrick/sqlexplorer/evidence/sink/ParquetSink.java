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
import jakarta.annotation.PreDestroy;
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

    /**
     * Built on first write, not in the constructor.
     *
     * <p>Parquet's file-level metadata is fixed when the writer is created,
     * and the capture context is not known until the import has run — this is
     * a {@code @Service}, constructed at startup. Deferring construction is
     * what lets a file state where it came from (sqlx-sy5).
     */
    private ParquetWriter<Object> objectWriter;

    /** Set before the first write, or the file carries no provenance. */
    private CaptureContext capture;

    public ParquetSink(ParquetConfig config) {
        this.config = config;
    }

    /**
     * Declare what the next file records. Must be called before the first
     * {@link #write}: the metadata is sealed into the writer when it is
     * created, and once a row has been written it is too late.
     */
    public synchronized void capturing(CaptureContext context) {
        if (objectWriter != null) {
            log.warn("capture context set after writing began; it will not reach the file");
            return;
        }
        this.capture = context;
    }

    /**
     * Where a capture is written: the configured path with the capture instant
     * folded into the stem.
     *
     * <p>One file per capture, not one file overwritten. Performance Schema is
     * cleared when the server restarts, so an overwritten file destroyed the
     * only record that a previous window was ever observed — and with it any
     * chance of noticing the gap between them (sqlx-sy5).
     *
     * <p>The stamp is ordered lexicographically as well as chronologically, so
     * the newest capture is the last name in a sorted listing and finding it
     * costs no parsing.
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
        Path stamped = Path.of(stem + "-" + stamp + extension);
        return parent == null ? stamped : parent.resolve(stamped);
    }

    private static final DateTimeFormatter STAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private ParquetWriter<Object> writer() throws IOException {
        if (objectWriter == null) {
            AvroParquetWriter.Builder<Object> parquetWriter =
                AvroParquetWriter.builder(new LocalOutputFile(fileFor(config.getFilename(), capture)));
            objectWriter = parquetWriter.withSchema(SqlProfile.getClassSchema())
                .withDictionaryEncoding(true)
                .withConf(new Configuration(false))
                .withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
                // absent rather than empty when nothing is known: a reader must
                // be able to tell "no provenance recorded" from "recorded as
                // nothing", which is the distinction sqlx-sy5 turns on
                .withExtraMetaData(capture == null ? Map.of() : capture.asMetadata())
                .build();
        }
        return objectWriter;
    }

    /**
     * Close the writer so the parquet footer is written and the file is
     * readable. This used to read a record back *before* closing, which meant
     * reading a file that had no footer yet — and printing the result to
     * stdout. The read is gone; anything verifying the sink can open the file
     * after this returns.
     */
    @PreDestroy
    public synchronized void close() throws IOException {
        if (objectWriter != null) {
            objectWriter.close();
        }
    }

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

    /** Read the profiles back — the file must be {@link #close()}d first. */
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

    public synchronized void write(SqlProfile sqlProfile) {
        try {
            writer().write(sqlProfile);
        } catch (IOException|RuntimeException e) {
            log.info("failed to write individual sql: {}", sqlProfile);
        }
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
