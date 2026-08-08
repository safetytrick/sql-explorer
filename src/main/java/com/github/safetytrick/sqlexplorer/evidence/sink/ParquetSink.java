package com.github.safetytrick.sqlexplorer.evidence.sink;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
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
    private final ParquetWriter<Object> objectWriter;

    public ParquetSink(ParquetConfig config) throws IOException {
        this.config = config;
        AvroParquetWriter.Builder<Object> parquetWriter =
            AvroParquetWriter.builder(new LocalOutputFile(Path.of(config.getFilename())));
        objectWriter = parquetWriter.withSchema(SqlProfile.getClassSchema())
            .withDictionaryEncoding(true)
            .withConf(
                new Configuration(false)
            )
            .withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
            .build();
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
        objectWriter.close();
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
            objectWriter.write(sqlProfile);
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
