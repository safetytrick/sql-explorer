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
            .withExtraMetaData(capture == null ? Map.of() : capture.asMetadata())
            .build();
    }

    static Path fileFor(String configuredPath, CaptureContext context) {
        Path base = Path.of(configuredPath);
        Path fileName = base.getFileName();
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
     * java.math.BigDecimal".
     */
    static PlainParquetConfiguration readerConfiguration() {
        return new PlainParquetConfiguration(
            Map.of("parquet.avro.serializable.classes", BigDecimal.class.getName()));
    }

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
