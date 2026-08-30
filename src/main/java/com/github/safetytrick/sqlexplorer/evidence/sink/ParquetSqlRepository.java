package com.github.safetytrick.sqlexplorer.evidence.sink;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.Optional;
import java.util.Objects;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.avro.ColumnReference;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.evidence.SqlImportStats;
import com.github.safetytrick.sqlexplorer.evidence.SqlRepository;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlSource;
import org.apache.avro.specific.SpecificData;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.filter2.compat.FilterCompat;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.io.LocalInputFile;
import org.apache.parquet.io.api.Binary;
import org.springframework.stereotype.Service;

import static org.apache.parquet.filter2.predicate.FilterApi.binaryColumn;
import static org.apache.parquet.filter2.predicate.FilterApi.eq;

@Service
public class ParquetSqlRepository implements SqlRepository {

    private static final Logger log = LoggerFactory.getLogger(ParquetSqlRepository.class);

    private final ParquetConfig config;

    public ParquetSqlRepository(ParquetConfig config) {
        this.config = config;
    }

    @Override
    public SqlProfile getById(String id) {
        return fetchWithFilter(
            FilterCompat.get(
                eq(binaryColumn("id"), Binary.fromString(id))))
            .findFirst().orElse(null);
    }

    @Override
    public Stream<SqlProfile> getTableQueries(TableReference table) {
        return getAllAnalyzedSql().filter(profile -> profile.getTables().stream().anyMatch(table::equalsNormalized));
    }

    @Override
    public Stream<SqlProfile> getQueriesForColumn(TableReference table, String column) {
        var columnRef = ColumnReference.newColumnReference(table, column);
        return getTableQueries(table)
            .filter(profile -> profile.constrainedBy(columnRef));
    }

    @Override
    public Stream<TableReference> getAllTableReferences() {
        return getAllAnalyzedSql()
            .flatMap(profile -> profile.getTables().stream())
            .distinct();
    }

    @Override
    public Stream<SqlProfile> getAllAnalyzedSql() {
        return fetchWithFilter(FilterCompat.NOOP);
    }

    @Override
    public SqlSource getSource() {
        return new ParquetSqlSource(config.getFilename());
    }

    @Override
    public SqlImportStats getImportStats() {
        // todo, implement me
        return new SqlImportStats(0,0,0,0,0,0,0);
    }

    /**
     * The most recent capture — the corpus — not every file on disk.
     *
     * <p>Reading them all would be wrong, not merely slow. Performance Schema
     * accumulates until the server restarts, so consecutive captures overlap
     * heavily and the union would hold several copies of most profiles,
     * inflating every count the app reports.
     *
     * <p>The older files are the archive, and they are kept for a different
     * question: whether there is a window between one capture's last observed
     * statement and the next capture's first, which is how a Performance
     * Schema reset becomes visible (sqlx-sy5). That reader is not this one.
     *
     * <p>Named stamps sort chronologically, so "latest" is the last entry in a
     * sorted listing. The un-stamped configured name is the legacy single-file
     * layout and is used only when no stamped capture exists.
     */
    public static Path latestCapture(String configuredPath) {
        Path base = Path.of(configuredPath);
        Path directory = base.getParent();
        Path fileName = base.getFileName();
        if (fileName == null) {
            return base;
        }
        String name = fileName.toString();
        int dot = name.lastIndexOf('.');
        String stem = dot < 0 ? name : name.substring(0, dot);
        String extension = dot < 0 ? "" : name.substring(dot);

        if (directory != null && directory.toFile().isDirectory()) {
            try (Stream<Path> entries = Files.list(directory)) {
                Optional<Path> newest = entries
                    .filter(path -> {
                        String candidate = String.valueOf(path.getFileName());
                        return candidate.startsWith(stem + "-") && candidate.endsWith(extension);
                    })
                    .max(Comparator.comparing(path -> String.valueOf(path.getFileName())));
                if (newest.isPresent()) {
                    return newest.get();
                }
            } catch (IOException e) {
                log.warn("could not list captures in {}: {}", directory, e.getMessage());
            }
        }
        return base;
    }

    private Stream<SqlProfile> fetchWithFilter(FilterCompat.Filter filter) {

        Path parquetFile = latestCapture(config.getFilename());

        if (parquetFile.toFile().exists()) {
            try (ParquetReader<SqlProfile> parquetReader = AvroParquetReader.<SqlProfile>builder(
                    new LocalInputFile(parquetFile), ParquetSink.readerConfiguration())
                .withDataModel(SpecificData.getForClass(SqlProfile.class))
                .build()) {


                return Stream.generate(() -> {
                        try {
                            return parquetReader.read();
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .takeWhile(Objects::nonNull)
                    .onClose(() -> {
                        try {
                            parquetReader.close();
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        return Stream.empty();
    }
}
