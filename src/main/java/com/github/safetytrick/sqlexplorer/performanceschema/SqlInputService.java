package com.github.safetytrick.sqlexplorer.performanceschema;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.analysis.JoinAcrossTypesAnalyzer;
import com.github.safetytrick.sqlexplorer.analysis.NormalizedQueryAnalyzer;
import com.github.safetytrick.sqlexplorer.analysis.SqlExplorer;
import com.github.safetytrick.sqlexplorer.core.AnalyzedSql;
import com.github.safetytrick.sqlexplorer.core.ColumnReference;
import com.github.safetytrick.sqlexplorer.core.JoinColumns;
import com.github.safetytrick.sqlexplorer.core.MergedAnalyzedSql;
import com.github.safetytrick.sqlexplorer.core.QueryActivityStatus;
import com.github.safetytrick.sqlexplorer.core.RuntimeMetrics;
import com.github.safetytrick.sqlexplorer.core.TableReference;
import com.github.safetytrick.sqlexplorer.performanceschema.fileloader.AbstractTsvSqlSource;
import com.github.safetytrick.sqlexplorer.schema.SqlMetadataService;
import com.github.safetytrick.sqlexplorer.service.AnalyzedSqlRepository;
import com.github.safetytrick.sqlexplorer.service.SqlImportStats;
import com.github.safetytrick.sqlexplorer.service.SqlRepository;
import com.github.safetytrick.sqlexplorer.source.MeteredSql;
import com.github.safetytrick.sqlexplorer.source.SqlInput;
import com.github.safetytrick.sqlexplorer.source.SqlSource;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimaps;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.JSQLParserException;
import org.hibernate.engine.jdbc.internal.BasicFormatterImpl;
import org.hibernate.engine.jdbc.internal.Formatter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class SqlInputService {

    private static final int MAX_NESTING_LEVEL = 50;

    private final Formatter sqlFormatter = new BasicFormatterImpl();
    private final ExecutorService executor = Executors.newWorkStealingPool();
    private final Map<String, Boolean> currentlyProcessing = new ConcurrentHashMap<>();

    private final boolean traceErrors;
    private final SqlMetadataService sqlMetadataService;
    private final SqlExplorer sqlExplorer;
    private final JoinAcrossTypesAnalyzer joinAcrossTypesAnalyzer;
    private final NormalizedQueryAnalyzer normalizedQueryAnalyzer;
    private final Timer performanceSchemaRowTimer;

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

    public SqlInputService(@Value("${sql-explorer.trace-errors}") boolean traceErrors, SqlMetadataService sqlMetadataService,
                           SqlExplorer sqlExplorer,
                           JoinAcrossTypesAnalyzer joinAcrossTypesAnalyzer,
                           NormalizedQueryAnalyzer normalizedQueryAnalyzer,
                           MeterRegistry meterRegistry) {
        this.traceErrors = traceErrors;
        this.sqlMetadataService = sqlMetadataService;
        this.sqlExplorer = sqlExplorer;
        this.joinAcrossTypesAnalyzer = joinAcrossTypesAnalyzer;
        this.normalizedQueryAnalyzer = normalizedQueryAnalyzer;
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
        while (futures.stream().anyMatch(((Predicate<Future<?>>) Future::isDone).negate())) {
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

        repository.allAnalyzedSql = mergeByDigest(repository.allAnalyzedSql);
        repository.allAnalyzedSql.sort(Comparator.naturalOrder());
        for (AnalyzedSql analyzedSql : repository.allAnalyzedSql) {
            for (TableReference table : analyzedSql.getTables()) {
                repository.tableNameAnalyzedSqlRepository.put(table, analyzedSql);
            }
        }

        SqlRepository sqlRepository = repository.build();
        log.info("Merge stats: merged={} notMerged={} totalPossible={}",
                 sqlRepository.getImportStats().getMerged(),
                 sqlRepository.getImportStats().getNotMerged(),
                 sqlRepository.getImportStats().getCount());

        log.info("Analysis failures: {}/{}, truncated={}", sqlRepository.getImportStats().getAnalysisFailed(),
                 sqlRepository.getImportStats().getCount(),
                 sqlRepository.getImportStats().getTruncated()
        );

        Map<String, Long> types = sqlRepository.getAllAnalyzedSql().collect(Collectors.groupingBy(AnalyzedSql::getType, Collectors.counting()));
        log.info("Types: {}", types);

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
        AnalyzedSql.Builder builder = AnalyzedSql.builder();

        String query = sqlFormatter.format(row.getQuery());
        RuntimeMetrics runtimeMetrics = RuntimeMetrics.builder()
            .fromPerformanceSchemaRow(row)
            .build();

        QueryActivityStatus status = runtimeMetrics.getLastSeen()
                .isAfter(LocalDateTime.ofInstant(toProcess.source().getCreatedOn().minus(30, ChronoUnit.DAYS), ZoneId.systemDefault()))
                && runtimeMetrics.getExecuted() > 1 ? QueryActivityStatus.ACTIVE : QueryActivityStatus.DORMANT;

        builder.lineNumber(lineNumber)
            .id(toProcess.source() + "-" + lineNumber)
            .sourceFileName(toProcess.source() instanceof AbstractTsvSqlSource tsvSource ?  tsvSource.getResource().getFilename() : null)
            .query(query)
            .type(firstToken)
            .schema(row.getSchema())
            .runtimeMetrics(runtimeMetrics)
            .queryActivityStatus(status)
            .isDml(row.getRowsAffected() > 1 || dmlTokens.contains(firstToken));

        if (row.getErrors() == row.getExecuted()) {
            //if the query always fails we might not be able to parse it and don't really care about it anyway
            return;
        }

        if (ignoredFirstTokens.contains(firstToken)) {
            builder.analysisSkipped(true);
        } else {
            try {
                int nestingLevel = getNestingLevel(query);
                if (nestingLevel > MAX_NESTING_LEVEL) {
                    throw new JSQLParserException("Skipping sql that exceeds max nesting level: max=" + MAX_NESTING_LEVEL + " level=" + nestingLevel);
                }

                int closedParens = getClosedParens(query);
                if (nestingLevel - closedParens > 1) {
                    builder.truncated(true);
                    throw new JSQLParserException("Unclosed parens: " + (nestingLevel - closedParens));
                }

                // optimistic merging decreases the amount of analysis we do but doesn't help
                // much with anything else, most of the file processing time is spent in the sql parser.
                long startNormalization = System.nanoTime();
                String normalizedDigest = normalizedQueryAnalyzer.normalizedDigest(query);
                if (normalizedDigest != null) {
                    builder.normalizedDigest(normalizedDigest);
                    if (repository.attemptMerge(normalizedDigest, builder)) {
                        return;
                    }
                }

                Duration normalizedParseTime = Duration.ofNanos(System.nanoTime() - startNormalization);
                if (normalizedParseTime.toSeconds() > 20) {
                    log.info("Parse time exceeded 20 second, aborting analysis");
                    builder.failureReason("parse time exceeded 20 seconds");
                } else {
                    sqlExplorer.analyzeSql(query, builder);

                    if (builder.view().getSchema() == null) {
                        String schema = row.getSchema();
                        if (schema == null) {
                            schema = deriveSchemaFrom(builder.view().getTables(), builder.view().getJoinColumns(), builder.view().getCriteriaColumns());
                        }
                        builder.schema(schema);
                    }

                    List<JoinColumns> mismatchedJoins = joinAcrossTypesAnalyzer.mismatchedJoins(builder.view().getSchema(), builder.view().getJoinColumns());
                    builder.mismatchedJoins(mismatchedJoins);
                }

            } catch (JSQLParserException e) {
                if (containsEof(e)) {
                    builder.truncated(true);
                } else {
                    builder.failureReason(getRootCauseMessage(e));
                }

                builder.tables(extractTablesByRegex(builder.view().getSchema(), query));

                if (traceErrors) {
                    log.info("Parse failed {}", row.getDigest(), e);
                }

                builder.analysisFailed(true);
            }
        }

        AnalyzedSql analyzedSql = builder.build();
        repository.addToIndex(analyzedSql);
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
            references.add(new TableReference(referencedSchema, table)
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

    private List<AnalyzedSql> mergeByDigest(List<AnalyzedSql> sqlList) {
        Map<String, List<AnalyzedSql>> groupedByDigest = sqlList.stream()
            .collect(Collectors.groupingBy(this::getDigestOrId));

        return groupedByDigest.values()
            .stream()
            .map(this::mergedSql)
            .collect(Collectors.toList());
    }

    private AnalyzedSql mergedSql(List<AnalyzedSql> alreadyGroupedList) {
        return alreadyGroupedList.size() > 1 ? new MergedAnalyzedSql(alreadyGroupedList) : alreadyGroupedList.get(0);
    }

    private String deriveSchemaFrom(List<TableReference> tableNames, List<JoinColumns> joinColumns, List<ColumnReference> criteriaColumns) {
        List<String> allColumns = new ArrayList<>();
        if (joinColumns != null) {
            joinColumns.stream()
                    .flatMap(join -> Stream.of(join.right().getColumn(), join.left().getColumn()))
                    .forEach(allColumns::add);
        }
        if (criteriaColumns != null) {
            criteriaColumns.stream()
                    .map(ColumnReference::getNormalizedColumn)
                    .forEach(allColumns::add);
        }

        return sqlMetadataService.findSchemaForTablesAndColumns(tableNames, allColumns);
    }

    private static String firstToken(String sql) {
        StringTokenizer tokenizer = new StringTokenizer(sql);
        if (tokenizer.hasMoreTokens()) {
            return tokenizer.nextToken().toUpperCase();
        }
        return null;
    }

    private String getDigestOrId(AnalyzedSql analyzedSql) {
        if (analyzedSql.getNormalizedDigest() != null) {
            return analyzedSql.getNormalizedDigest();
        } else if (analyzedSql.getDigest() != null) {
            return analyzedSql.getDigest();
        } else if (analyzedSql.getId() != null) {
            return analyzedSql.getId();
        }
        return analyzedSql.getQuery();
    }

    public static class SqlRepositoryBuilder {
        private final Object lock = new Object();
        private final SqlSource source;
        private final SqlImportStats.Builder statsBuilder = SqlImportStats.builder();
        // only by normalized digest
        private final ListMultimap<String, AnalyzedSql> digestAnalyzedSqlRepository = Multimaps.synchronizedListMultimap(ArrayListMultimap.create());
        private List<AnalyzedSql> allAnalyzedSql = new ArrayList<>();
        private final ListMultimap<TableReference, AnalyzedSql> tableNameAnalyzedSqlRepository = ArrayListMultimap.create();

        public SqlRepositoryBuilder(SqlSource source) {
            this.source = source;
        }

        private boolean attemptMerge(String normalizedDigest, AnalyzedSql.Builder builder) {

            if (!digestAnalyzedSqlRepository.containsKey(normalizedDigest)) {
                statsBuilder.notMerged();
                return false;
            }

            statsBuilder.merged();

            AnalyzedSql existingEntry = digestAnalyzedSqlRepository.get(normalizedDigest).get(0);
            builder.joinColumns(existingEntry.getJoinColumns())
                .analysisFailed(existingEntry.isAnalysisFailed())
                .schema(existingEntry.getSchema())
                .criteriaColumns(existingEntry.getCriteriaColumns())
                .failureReason(existingEntry.getAnalysisFailureReason())
                .isDml(existingEntry.isDml())
                .mismatchedJoins(existingEntry.getMismatchedJoins())
                .truncated(existingEntry.isTruncated())
                .tables(existingEntry.getTables())
                .type(existingEntry.getType());

            AnalyzedSql analyzedSql = builder.build();
            addToIndex(analyzedSql);

            return true;
        }

        private void addToIndex(AnalyzedSql analyzedSql) {

            if (analyzedSql.getNormalizedDigest() != null) {
                String digest = analyzedSql.getNormalizedDigest();
                digestAnalyzedSqlRepository.put(digest, analyzedSql);
            }

            reportStatistics(analyzedSql);

            synchronized (lock) {
                this.allAnalyzedSql.add(analyzedSql);
            }
        }

        private void reportStatistics(AnalyzedSql analyzedSql) {
            if (analyzedSql.isAnalysisSkipped()) {
                statsBuilder.analysisSkipped();
            }
            if (analyzedSql.isAnalysisFailed()) {
                statsBuilder.analysisFailed();
            }

            if (analyzedSql.isTruncated()) {
                statsBuilder.truncated();
            }

            if (analyzedSql.isDml()) {
                statsBuilder.dml();
            }

            statsBuilder.count();
        }

        public SqlRepository build() {
            return new AnalyzedSqlRepository(source, statsBuilder.build(), allAnalyzedSql);
        }
    }
}
