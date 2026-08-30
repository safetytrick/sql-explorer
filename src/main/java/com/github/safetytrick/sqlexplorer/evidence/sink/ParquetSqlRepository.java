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
     * Every capture on disk, oldest name first — the archive.
     *
     * <p>One owner for what a capture file is <em>called</em>. The stamped
     * name is written by {@link ParquetSink#fileFor}, and both this and
     * {@link #latestCapture} have to agree with it; spelling the same filename
     * vocabulary twice is how two readers of one convention drift apart.
     *
     * <p>Name order is not capture order for the purpose of gap detection —
     * see {@link CaptureArchive}, which orders by the footer's recorded
     * instant. It is only good enough to find the newest, because the stamp is
     * lexicographic.
     */
    public static List<Path> captures(String configuredPath) {
        Path base = Path.of(configuredPath);
        Path directory = base.getParent();
        Path fileName = base.getFileName();
        // a root path names no file, so there is no stem to match against
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
     * Schema reset becomes visible. That reader is {@link CaptureArchive}
     * (sqlx-sy5).
     *
     * <p>Named stamps sort chronologically, so "latest" is the last entry in a
     * sorted listing. The un-stamped configured name is the legacy single-file
     * layout and is used only when no stamped capture exists.
     */
    public static Path latestCapture(String configuredPath) {
        List<Path> captures = captures(configuredPath);
        for (int i = captures.size() - 1; i >= 0; i--) {
            if (isSealed(captures.get(i))) {
                return captures.get(i);
            }
        }
        return Path.of(configuredPath);
    }

    /**
     * Whether a capture has a footer, and so can be read at all.
     *
     * <p>The newest name is normally the running process's <em>own</em> open
     * capture. Parquet writes its footer at close, which for {@link
     * ParquetSink} is {@code @PreDestroy}, so from the moment an import starts
     * until the application stops, the newest file on disk is a zero-length
     * placeholder. Observed on a live instance: three stamped captures, the
     * newest unreadable for the whole life of the process.
     *
     * <p>Nothing broke, because the corpus is served from heap today and this
     * path has no reader — but it is the answer to "where is the corpus", and
     * it would have started returning an empty result the moment sqlx-4ww made
     * it load-bearing. Skipping backwards to the newest sealed capture costs
     * one footer seek and normally stops at the first candidate.
     *
     * <p>Not the same question as "does it carry provenance": a legacy file
     * with no {@code sqlexplorer.*} metadata is still a perfectly good corpus.
     */
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
