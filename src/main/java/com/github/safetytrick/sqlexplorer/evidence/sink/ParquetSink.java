package com.github.safetytrick.sqlexplorer.evidence.sink;

import java.io.IOException;
import java.nio.file.Path;

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

    @PreDestroy
    public synchronized void close() throws IOException {
        Path parquetFile = Path.of(config.getFilename());
        try (ParquetReader<SqlProfile> parquetReader = AvroParquetReader.<SqlProfile>builder(
                new LocalInputFile(parquetFile), new PlainParquetConfiguration())
            .build()) {

            System.out.println(parquetReader.read());
        }
        objectWriter.close();
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
