package com.github.safetytrick.sqlexplorer.evidence.sink;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.avro.ColumnReference;
import com.github.safetytrick.sqlexplorer.avro.RuntimeMetrics;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.evidence.SqlImportStats;
import com.github.safetytrick.sqlexplorer.evidence.SqlRepository;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlSource;
import org.apache.avro.specific.SpecificData;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.filter2.compat.FilterCompat;
import org.apache.parquet.hadoop.ParquetFileReader;
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
    public SqlProfile getByDigest(String digest) {
        SqlProfile pushed = fetchWithFilter(
            FilterCompat.get(eq(binaryColumn("normalizedDigest"), Binary.fromString(digest))))
            .findFirst().orElse(null);
        if (pushed != null) {
            return pushed;
        }
        try (Stream<SqlProfile> all = getAllAnalyzedSql()) {
            return all.filter(profile -> carriesDigest(profile, digest)).findFirst().orElse(null);
        }
    }

    private static boolean carriesDigest(SqlProfile profile, String digest) {
        if (digest.equals(profile.getNormalizedDigest()) || digest.equals(profile.getDigest())) {
            return true;
        }
        RuntimeMetrics metrics = profile.getRuntimeMetrics();
        if (metrics == null) {
            return false;
        }
        return digest.equals(metrics.getDigest())
            || (metrics.getDigestList() != null && metrics.getDigestList().contains(digest));
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
        return new SqlImportStats(0,0,0,0,0,0,0,0,0);
    }

    public static List<Path> captures(String configuredPath) {
        Path base = Path.of(configuredPath);
        Path directory = base.getParent();
        Path fileName = base.getFileName();
        if (fileName == null || directory == null || !directory.toFile().isDirectory()) {
            return List.of();
        }
        String name = fileName.toString();
        int dot = name.lastIndexOf('.');
        String stem = dot < 0 ? name : name.substring(0, dot);
        String extension = dot < 0 ? "" : name.substring(dot);

        try (Stream<Path> entries = Files.list(directory)) {
            return entries
                .filter(path -> {
                    String candidate = String.valueOf(path.getFileName());
                    return candidate.startsWith(stem + "-") && candidate.endsWith(extension);
                })
                .sorted(Comparator.comparing(path -> String.valueOf(path.getFileName())))
                .toList();
        } catch (IOException e) {
            log.warn("could not list captures in {}: {}", directory, e.getMessage());
            return List.of();
        }
    }

    private static final java.util.regex.Pattern STAMPED = java.util.regex.Pattern.compile("\\d{8}T\\d{6}Z(\\..*)?");

    public static List<Path> capturesOf(String configuredPath, String sourceRef) {
        String token = ParquetSink.fileToken(sourceRef);
        if (token.isEmpty()) {
            return List.of();
        }
        Path base = Path.of(configuredPath);
        Path fileName = base.getFileName();
        if (fileName == null) {
            return List.of();
        }
        String name = fileName.toString();
        int dot = name.lastIndexOf('.');
        String stem = dot < 0 ? name : name.substring(0, dot);
        String prefix = stem + "-" + token;
        return captures(configuredPath).stream()
            .filter(path -> {
                String file = String.valueOf(path.getFileName());
                return file.startsWith(prefix) && STAMPED.matcher(file.substring(prefix.length())).matches();
            })
            .toList();
    }

    public static Path latestCaptureOf(String configuredPath, String sourceRef) {
        List<Path> captures = capturesOf(configuredPath, sourceRef);
        for (int i = captures.size() - 1; i >= 0; i--) {
            if (isSealed(captures.get(i))) {
                return captures.get(i);
            }
        }
        return null;
    }

    public static Path latestCapture(String configuredPath) {
        List<Path> captures = captures(configuredPath);
        for (int i = captures.size() - 1; i >= 0; i--) {
            if (isSealed(captures.get(i))) {
                return captures.get(i);
            }
        }
        return Path.of(configuredPath);
    }

    private static boolean isSealed(Path capture) {
        try (ParquetFileReader reader = ParquetFileReader.open(new LocalInputFile(capture))) {
            return reader.getFooter() != null;
        } catch (IOException | RuntimeException e) {
            log.debug("skipping unreadable capture {}: {}", capture, e.getMessage());
            return false;
        }
    }

    private Stream<SqlProfile> fetchWithFilter(FilterCompat.Filter filter) {

        Path parquetFile = latestCapture(config.getFilename());

        if (parquetFile.toFile().exists()) {
            try (ParquetReader<SqlProfile> parquetReader = AvroParquetReader.<SqlProfile>builder(
                    new LocalInputFile(parquetFile), ParquetSink.readerConfiguration())
                .withDataModel(SpecificData.getForClass(SqlProfile.class))
                .withFilter(filter)
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
