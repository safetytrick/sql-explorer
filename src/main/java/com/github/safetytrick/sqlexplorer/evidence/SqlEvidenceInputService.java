package com.github.safetytrick.sqlexplorer.evidence;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.analysis.SqlAnalysisService;
import com.github.safetytrick.sqlexplorer.analysis.parser.NormalizedQueryDigest;
import com.github.safetytrick.sqlexplorer.avro.QueryActivityStatus;
import com.github.safetytrick.sqlexplorer.avro.RuntimeMetrics;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.evidence.fileloader.AbstractTsvSqlSource;
import com.github.safetytrick.sqlexplorer.evidence.sink.ParquetSink;
import com.github.safetytrick.sqlexplorer.evidence.source.MeteredSql;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlInput;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlSource;
import com.github.safetytrick.sqlexplorer.model.MergedSqlProfile;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimaps;
import extensions.com.github.safetytrick.sqlexplorer.avro.SqlProfile.SqlProfileExtension;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import manifold.util.concurrent.ConcurrentHashSet;
import net.sf.jsqlparser.JSQLParserException;
import org.apache.avro.AvroMissingFieldException;
import org.hibernate.engine.jdbc.internal.BasicFormatterImpl;
import org.hibernate.engine.jdbc.internal.Formatter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class SqlEvidenceInputService {

    private static final int MAX_NESTING_LEVEL = 50;

    private final Formatter sqlFormatter = new BasicFormatterImpl();
    private final ExecutorService executor = Executors.newWorkStealingPool();
    private final Map<String, Boolean> currentlyProcessing = new ConcurrentHashMap<>();

    private final boolean traceErrors;
    private final ParquetSink parquetSink;
    private final SqlAnalysisService sqlAnalysisService;
    private final NormalizedQueryDigest normalizedQueryDigest;
    private final Timer performanceSchemaRowTimer;
    private final Set<String> missingFieldExceptions = new ConcurrentHashSet<>();

    // an optimization to ignore parsing sql we likely can't and likely can't find useful data from.
    private static final Set<String> ignoredFirstTokens = Set.of("ALTER",
            "ANALYZE",
            "BEGIN",
            "CALL",
            "CREATE",
            "CHECK",
            "DESC",
            "DO",
            "DROP",
            "EXPLAIN",
            "FLUSH",
            "GRANT",
            "KILL",
            "LOCK",
            "REVOKE",
            "RENAME",
            "ROLLBACK",
            "SHOW",
            "SET",
            "START",
            "UNLOCK",
            "USE");

    private static final Set<String> dmlTokens = Set.of("INSERT", "UPDATE", "DELETE");

    public SqlEvidenceInputService(@Value("${sql-explorer.trace-errors}") boolean traceErrors, ParquetSink parquetSink,
                                   SqlAnalysisService sqlAnalysisService,
                                   NormalizedQueryDigest normalizedQueryDigest,
                                   MeterRegistry meterRegistry) {
        this.traceErrors = traceErrors;
        this.parquetSink = parquetSink;
        this.sqlAnalysisService = sqlAnalysisService;
        this.normalizedQueryDigest = normalizedQueryDigest;
        performanceSchemaRowTimer = meterRegistry.timer("performance_schema_process_row");
    }

    /**
     * For each exported entry from the performance schema
     * gather metadata about the query, runtime metrics, etc.
     * generate a normalized digest by discarding or normalizing irrelevant query parts (alias names)
     * check the repository to see if this query has already been indexed, if so add another record of this query to the index so their stats can be merged later
     * run primary query analyzers to extract tables, joins, etc.
     * derive schema from the performance schema record, or tables and columns in the query
     * run join across types analyzer
     *
     * @param source metadata about the source of the performance schema data
     * @return a completely initialized SqlRepository
     *
     * @throws IOException for IO failures occur parsing the performance schema csv
     */
    @Timed("performance_schema_import")
    public SqlRepository fromPerformanceSchema(SqlSource source, Stream<SqlInput> inputStream) throws IOException {
        List<Future<?>> futures = new ArrayList<>();

        SqlRepositoryBuilder repository = new SqlRepositoryBuilder(source);
        inputStream.forEach(sqlInput ->
            futures.add(
                executor.submit(
                    performanceSchemaRowTimer.wrap(() -> {
                        String rowName = getRowName(sqlInput);
                        try {
                            currentlyProcessing.put(rowName, true);
                            processRow(repository, sqlInput);
                        } catch (Exception e) {
                            if (e instanceof AvroMissingFieldException) {
                                missingFieldExceptions.add(e.toString());
                            }
                            log.error("Row Processing failed: {}", sqlInput.row(), e);
                            throw e;
                        } finally {
                            currentlyProcessing.remove(rowName);
                        }
                    })
                )
            )
        );

        int iteration = 0;
        while (!futures.stream().allMatch(Future::isDone)) {
            iteration++;
            long completed = futures.stream().filter(Future::isDone).count();
            if (iteration % 5 == 0) {
                log.info("Completed {} of {} jobs, currently processing: {}", completed, futures.size(), currentlyProcessing.keySet());
            }
            try {
                //noinspection BusyWait
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        repository.sqlProfiles = mergeByDigest(repository.sqlProfiles);
        repository.sqlProfiles.sort(SqlProfileExtension.comparator);
        for (SqlProfile sqlProfile : repository.sqlProfiles) {
            for (TableReference table : sqlProfile.getTables()) {
                repository.tableNameAnalyzedSqlRepository.put(table, sqlProfile);
            }
        }

        SqlRepository sqlRepository = repository.build();
        log.info("Merge stats: merged={} notMerged={} totalPossible={}",
                 sqlRepository.getImportStats().merged(),
                 sqlRepository.getImportStats().notMerged(),
                 sqlRepository.getImportStats().count());

        log.info("Analysis failures: {}/{}, truncated={}", sqlRepository.getImportStats().analysisFailed(),
                 sqlRepository.getImportStats().count(),
                 sqlRepository.getImportStats().truncated()
        );

        Map<String, Long> types = sqlRepository.getAllAnalyzedSql().collect(Collectors.groupingBy(SqlProfile::getType, Collectors.counting()));
        log.info("Types: {}", types);

        for (String missingFieldException : missingFieldExceptions) {
            log.info("AvroBuild Exceptions: {}", missingFieldException);
        }

        return sqlRepository;
    }

    private String getRowName(SqlInput row) {
        if (row.row().getDigest() != null) {
            return row.row().getDigest();
        }
        return row.source().getSourceRef() + "#" + row.rank();
    }

    private void processRow(SqlRepositoryBuilder repository, SqlInput toProcess) {
        MeteredSql row = toProcess.row();
        if (row.getQuery() == null) {
            // a few entries in the mysql export are null
            return;
        }
        String firstToken = firstToken(row.getQuery());
        int lineNumber = toProcess.rank();
        SqlProfile.Builder builder = SqlProfile.newBuilder();

        String query = sqlFormatter.format(row.getQuery());
        RuntimeMetrics runtimeMetrics = RuntimeMetrics.newBuilder()
            .fromPerformanceSchemaRow(row)
            .completeBuild();

        QueryActivityStatus status = runtimeMetrics.getLastSeen()
                .isAfter(LocalDateTime.ofInstant(toProcess.source().getCreatedOn().minus(30, ChronoUnit.DAYS), ZoneId.systemDefault()))
                && runtimeMetrics.getExecuted() > 1 ? QueryActivityStatus.ACTIVE : QueryActivityStatus.DORMANT;

        builder.setLineNumber(lineNumber)
            .setId(toProcess.source() + "-" + lineNumber)
            .setSourceFileName(toProcess.source() instanceof AbstractTsvSqlSource tsvSource ?  tsvSource.getResource().getFilename() : null)
            .setQuery(query)
            .setType(firstToken)
            .setSchemaName(row.getSchemaName() != null ? row.getSchemaName() : "dual")
            .setRuntimeMetrics(runtimeMetrics)
            .setQueryActivityStatus(status)
            .setIsDml(row.getRowsAffected() > 1 || dmlTokens.contains(firstToken));

        if (row.getErrors() == row.getExecuted()) {
            //if the query always fails we might not be able to parse it and don't really care about it anyway
            return;
        }

        if (ignoredFirstTokens.contains(firstToken)) {
            builder.setAnalysisSkipped(true);
        } else {
            try {
                int nestingLevel = getNestingLevel(query);
                if (nestingLevel > MAX_NESTING_LEVEL) {
                    throw new JSQLParserException("Skipping sql that exceeds max nesting level: max=" + MAX_NESTING_LEVEL + " level=" + nestingLevel);
                }

                int closedParens = getClosedParens(query);
                if (nestingLevel - closedParens > 1) {
                    builder.setTruncated(true);
                    throw new JSQLParserException("Unclosed parens: " + (nestingLevel - closedParens));
                }

                // optimistic merging decreases the amount of analysis we do but doesn't help
                // much with anything else, most of the file processing time is spent in the sql parser.
                long startNormalization = System.nanoTime();
                String normalizedDigest = normalizedQueryDigest.normalizedDigest(query);
                if (normalizedDigest != null) {
                    builder.setNormalizedDigest(normalizedDigest);
                    if (repository.attemptMerge(normalizedDigest, builder)) {
                        return;
                    }
                }

                Duration normalizedParseTime = Duration.ofNanos(System.nanoTime() - startNormalization);
                if (normalizedParseTime.toSeconds() > 20) {
                    log.info("Parse time exceeded 20 second, aborting analysis");
                    builder.setAnalysisFailureReason("parse time exceeded 20 seconds");
                } else {
                    sqlAnalysisService.analyzeSql(query, builder);
                }

            } catch (JSQLParserException e) {
                if (containsEof(e)) {
                    builder.setTruncated(true);
                } else {
                    builder.setAnalysisFailureReason(getRootCauseMessage(e));
                }

                builder.setTables(extractTablesByRegex(builder.getSchemaName(), query));

                if (traceErrors) {
                    log.info("Parse failed {}", row.getDigest(), e);
                }

                builder.setAnalysisFailed(true);
            }
        }

        SqlProfile sqlProfile = builder.completeBuild(row.getSchemaName());
        parquetSink.write(sqlProfile);

        repository.addToIndex(sqlProfile);
    }

    /**
     * used to extract tables when parsing the whole query fails
     */
    private static final Pattern FROM_TABLE = Pattern.compile("\\s(FROM|JOIN)\\s+(`?(?<schema>[a-zA-Z_]\\w*)`?\\s*\\.\\s*)?`?(?<table>[a-zA-Z_]\\w*)`?\\s+", Pattern.MULTILINE|Pattern.CASE_INSENSITIVE);

    private List<TableReference> extractTablesByRegex(String schema, String query) {
        Matcher matcher = FROM_TABLE.matcher(query);
        List<TableReference> references = new ArrayList<>();
        while (matcher.find()) {
            String referencedSchema = matcher.group("schema");
            String table = matcher.group("table");
            references.add(TableReference.newTableReference(referencedSchema, table)
                    .withDefaultSchema(schema));

        }
        return references;
    }

    private String getRootCauseMessage(Throwable e) {
        String message = null;
        while (e != null) {
            message = e.getMessage();
            e = e.getCause();
        }
        return message;
    }

    private boolean containsEof(Throwable exception) {
        while (exception != null) {
            if (exception.getMessage().contains("Encountered unexpected token:<EOF>")) {
                return true;
            }
            exception = exception.getCause();

        }
        return false;
    }

    private int getNestingLevel(String query) {
        int openParens = 0;
        int start = 0;
        int index = query.indexOf('(', start);
        while (index != -1) {
            openParens++;
            start = index + 1;
            index = query.indexOf('(', start);
        }
        return openParens;
    }

    private int getClosedParens(String query) {
        int openParens = 0;
        int start = 0;
        int index = query.indexOf(')', start);
        while (index != -1) {
            openParens++;
            start = index + 1;
            index = query.indexOf(')', start);
        }
        return openParens;
    }

    private List<SqlProfile> mergeByDigest(List<SqlProfile> sqlList) {
        Map<String, List<SqlProfile>> groupedByDigest = sqlList.stream()
            .collect(Collectors.groupingBy(this::getDigestOrId));

        return groupedByDigest.values()
            .stream()
            .map(this::mergedSql)
            .collect(Collectors.toList());
    }

    private SqlProfile mergedSql(List<SqlProfile> alreadyGroupedList) {
        return alreadyGroupedList.size() > 1 ? new MergedSqlProfile(alreadyGroupedList) : alreadyGroupedList.getFirst();
    }



    private static String firstToken(String sql) {
        StringTokenizer tokenizer = new StringTokenizer(sql);
        if (tokenizer.hasMoreTokens()) {
            return tokenizer.nextToken().toUpperCase();
        }
        return null;
    }

    private String getDigestOrId(SqlProfile sqlProfile) {
        if (sqlProfile.getNormalizedDigest() != null) {
            return sqlProfile.getNormalizedDigest();
        } else if (sqlProfile.getDigest() != null) {
            return sqlProfile.getDigest();
        } else if (sqlProfile.getId() != null) {
            return sqlProfile.getId();
        }
        return sqlProfile.getQuery();
    }

    public static class SqlRepositoryBuilder {
        private final Object lock = new Object();
        private final SqlSource source;
        private final SqlImportStats.Builder statsBuilder = SqlImportStats.builder();
        // only by normalized digest
        private final ListMultimap<String, SqlProfile> digestAnalyzedSqlRepository = Multimaps.synchronizedListMultimap(ArrayListMultimap.create());
        private List<SqlProfile> sqlProfiles = new ArrayList<>();
        private final ListMultimap<TableReference, SqlProfile> tableNameAnalyzedSqlRepository = ArrayListMultimap.create();

        public SqlRepositoryBuilder(SqlSource source) {
            this.source = source;
        }

        private boolean attemptMerge(String normalizedDigest, SqlProfile.Builder builder) {

            if (!digestAnalyzedSqlRepository.containsKey(normalizedDigest)) {
                statsBuilder.notMerged();
                return false;
            }

            statsBuilder.merged();

            SqlProfile existingEntry = digestAnalyzedSqlRepository.get(normalizedDigest).getFirst();
            builder.setJoinColumns(existingEntry.getJoinColumns())
                .setAnalysisFailed(existingEntry.getAnalysisFailed())
                .setSchemaName(existingEntry.getSchemaName())
                .setCriteriaColumns(existingEntry.getCriteriaColumns())
                .setAnalysisFailureReason(existingEntry.getAnalysisFailureReason())
                .setIsDml(existingEntry.getIsDml())
                .setMismatchedJoins(existingEntry.getMismatchedJoins())
                .setTruncated(existingEntry.getTruncated())
                .setTables(existingEntry.getTables())
                .setType(existingEntry.getType());

            SqlProfile sqlProfile = builder.completeBuild(existingEntry.getSchemaName());
            addToIndex(sqlProfile);

            return true;
        }

        private void addToIndex(SqlProfile sqlProfile) {

            if (sqlProfile.getNormalizedDigest() != null) {
                String digest = sqlProfile.getNormalizedDigest();
                digestAnalyzedSqlRepository.put(digest, sqlProfile);
            }

            reportStatistics(sqlProfile);

            synchronized (lock) {
                this.sqlProfiles.add(sqlProfile);
            }
        }

        private void reportStatistics(SqlProfile sqlProfile) {
            if (sqlProfile.getAnalysisSkipped()) {
                statsBuilder.analysisSkipped();
            }
            if (sqlProfile.getAnalysisFailed()) {
                statsBuilder.analysisFailed();
            }

            if (sqlProfile.getTruncated()) {
                statsBuilder.truncated();
            }

            if (sqlProfile.getIsDml()) {
                statsBuilder.dml();
            }

            statsBuilder.count();
        }

        public SqlRepository build() {
            return new AnalyzedSqlRepository(source, statsBuilder.build(), sqlProfiles);
        }
    }
}
