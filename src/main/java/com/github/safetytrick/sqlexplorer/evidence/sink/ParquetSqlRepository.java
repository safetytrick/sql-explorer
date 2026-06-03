package com.github.safetytrick.sqlexplorer.evidence.sink;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
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

    private Stream<SqlProfile> fetchWithFilter(FilterCompat.Filter filter) {

        Path parquetFile = Path.of(config.getFilename());

        if (parquetFile.toFile().exists()) {
            try (ParquetReader<SqlProfile> parquetReader = AvroParquetReader.<SqlProfile>builder(
                    new LocalInputFile(parquetFile), new PlainParquetConfiguration())
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
