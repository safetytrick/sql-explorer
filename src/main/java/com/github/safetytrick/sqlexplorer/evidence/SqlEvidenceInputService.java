package com.github.safetytrick.sqlexplorer.evidence;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
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
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.analysis.AnalyzerVersion;
import com.github.safetytrick.sqlexplorer.analysis.IgnoredSchemas;
import com.github.safetytrick.sqlexplorer.analysis.RegexTableExtractor;
import com.github.safetytrick.sqlexplorer.analysis.SqlAnalysisService;
import com.github.safetytrick.sqlexplorer.analysis.StatementTypes;
import com.github.safetytrick.sqlexplorer.analysis.parser.NormalizedQueryDigest;
import com.github.safetytrick.sqlexplorer.analysis.parser.RepeatLocator;
import com.github.safetytrick.sqlexplorer.avro.MergedChild;
import com.github.safetytrick.sqlexplorer.avro.QueryActivityStatus;
import com.github.safetytrick.sqlexplorer.avro.RepeatedBlock;
import com.github.safetytrick.sqlexplorer.avro.RuntimeMetrics;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.evidence.fileloader.AbstractTsvSqlSource;
import com.github.safetytrick.sqlexplorer.evidence.sink.CaptureContext;
import com.github.safetytrick.sqlexplorer.evidence.sink.ParquetSink;
import com.github.safetytrick.sqlexplorer.evidence.source.MeteredSql;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlInput;
import com.github.safetytrick.sqlexplorer.evidence.source.SqlSource;
import com.github.safetytrick.sqlexplorer.model.MergedSqlProfile;
import com.github.safetytrick.sqlexplorer.model.QueryKind;
import com.github.safetytrick.sqlexplorer.schema.SqlSchemaRepository;
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

    private static final int MAX_OPEN_PARENS = 200;
    private static final Duration MAX_PARSE = Duration.ofSeconds(20);

    private final Formatter sqlFormatter = new BasicFormatterImpl();
    private final ExecutorService executor = Executors.newWorkStealingPool();
    private final Map<String, Boolean> currentlyProcessing = new ConcurrentHashMap<>();

    private final boolean traceErrors;
    private final ParquetSink parquetSink;
    private final SqlAnalysisService sqlAnalysisService;
    private final NormalizedQueryDigest normalizedQueryDigest;
    private final Timer performanceSchemaRowTimer;
    private final Set<String> missingFieldExceptions = new ConcurrentHashSet<>();
    private final IgnoredSchemas ignoredSchemas;
    private final RegexTableExtractor tableExtractor = new RegexTableExtractor();

    static final Set<String> ignoredFirstTokens = Set.of("ALTER",
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
            "PURGE",
            "RELEASE",
            "REVOKE",
            "RENAME",
            "ROLLBACK",
            "SAVEPOINT",
            "SHOW",
            "SET",
            "START",
            "UNLOCK",
            "USE");

    private static final String SHOW = "SHOW";

    public SqlEvidenceInputService(@Value("${sql-explorer.trace-errors}") boolean traceErrors, ParquetSink parquetSink,
                                   SqlAnalysisService sqlAnalysisService,
                                   NormalizedQueryDigest normalizedQueryDigest,
                                   MeterRegistry meterRegistry,
                                   IgnoredSchemas ignoredSchemas) {
        this.traceErrors = traceErrors;
        this.parquetSink = parquetSink;
        this.sqlAnalysisService = sqlAnalysisService;
        this.normalizedQueryDigest = normalizedQueryDigest;
        this.ignoredSchemas = ignoredSchemas;
        performanceSchemaRowTimer = meterRegistry.timer("performance_schema_process_row");
    }

    @Timed("performance_schema_import")
    public SqlRepository fromPerformanceSchema(SqlSource source, Stream<SqlInput> inputStream, SqlSchemaRepository schema) {
        return fromPerformanceSchema(source, inputStream, schema, AnalysisContext.ANALYSE_EVERYTHING);
    }

    public record AnalysisContext(AnalysisReuse reuse, boolean schemaCurrent) {
        public static final AnalysisContext ANALYSE_EVERYTHING = new AnalysisContext(AnalysisReuse.NONE, true);
    }

    public SqlRepository fromPerformanceSchema(SqlSource source, Stream<SqlInput> inputStream, SqlSchemaRepository schema,
                                               AnalysisContext context) {
        SqlRepositoryBuilder repository = new SqlRepositoryBuilder(source);
        List<Future<?>> futures = inputStream.map(sqlInput ->
            executor.submit(
                performanceSchemaRowTimer.wrap(() -> {
                    String rowName = getRowName(sqlInput);
                    try {
                        currentlyProcessing.put(rowName, true);
                        processRow(repository, sqlInput, schema, context);
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
        ).collect(Collectors.toList());

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

        repository.writeParquet(parquetSink);
        log.info("Parquet rows written: {} (one per profile; merging is rebuilt on read)",
                 repository.parquetRowCount());

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

    private void processRow(SqlRepositoryBuilder repository, SqlInput toProcess, SqlSchemaRepository schema,
                            AnalysisContext context) {
        MeteredSql row = toProcess.row();
        if (row.getQuery() == null) {
            return;
        }
        if (row.getDigest() == null) {
            // Performance Schema's overflow bucket: once the digest table is
            // full, every statement without a row of its own is counted here,
            // across every schema. It is not a statement, and its executions
            // are already reported as lost under "Room to record". It normally
            // has no text either, but has been read carrying another
            // statement's (sqlx-ugf), so the digest is what identifies it.
            return;
        }
        String firstToken = firstToken(row.getQuery());
        String textType = StatementTypes.fromText(row.getQuery());
        int lineNumber = toProcess.rank();
        SqlProfile.Builder builder = SqlProfile.newBuilder();

        String query = sqlFormatter.format(row.getQuery());
        RuntimeMetrics runtimeMetrics = RuntimeMetrics.newBuilder()
            .fromPerformanceSchemaRow(row)
            .completeBuild();

        QueryActivityStatus status = runtimeMetrics.getLastSeen()
                .isAfter(LocalDateTime.ofInstant(toProcess.source().getCreatedOn().minus(30, ChronoUnit.DAYS), ZoneId.systemDefault()))
                && runtimeMetrics.getExecuted() > 1 ? QueryActivityStatus.ACTIVE : QueryActivityStatus.DORMANT;

        String sourceFileName = toProcess.source() instanceof AbstractTsvSqlSource tsvSource
            ? tsvSource.getResource().getFilename() : null;

        builder.setLineNumber(lineNumber)
            .setId(toProcess.source() + "-" + lineNumber)
            .setSourceFileName(sourceFileName)
            .setSourcedFiles(sourceFileName == null ? List.of() : List.of(sourceFileName))
            .setSources(toProcess.source().getSourceRef() == null ? List.of() : List.of(toProcess.source().getSourceRef()))
            .setQuery(query)
            .setType(textType)
            .setSchemaName(row.getSchemaName() != null ? row.getSchemaName() : "dual")
            .setRuntimeMetrics(runtimeMetrics)
            .setQueryActivityStatus(status)
            .setIsDml(QueryKind.of(textType, row.getRowsAffected()).isWrite());

        if (row.getErrors() == row.getExecuted()) {
            return;
        }

        SqlProfile prior = context.reuse().find(row.getDigest(), row.getSchemaName());
        if (prior != null) {
            copyAnalysis(prior, builder);
            markDml(builder);
            repository.reused();
            if (prior.getNormalizedDigest() != null && repository.attemptMerge(prior.getNormalizedDigest(), builder)) {
                return;
            }
            repository.addToIndex(builder.completeBuild(row.getSchemaName()));
            return;
        }
        if (!context.schemaCurrent()) {
            repository.pending();
            return;
        }
        builder.setAnalyzerVersion(AnalyzerVersion.CURRENT)
            .setSessionSchema(row.getSchemaName());

        List<TableReference> tables = tableExtractor.extractTables(row.getSchemaName(), query);
        List<TableReference> unknownTables =
            tableExtractor.unknownTables(row.getSchemaName(), query, schema);

        int nestingLevel = getOpenParens(query);

        if (ignoredFirstTokens.contains(firstToken) || ignoredSchemas.ignoreAll(tables)) {
            builder.setTables(SHOW.equals(firstToken) ? List.of() : tables);
            builder.setAnalysisSkipped(true);
            flagUnknownTables(builder, unknownTables);
        } else {
            try {
                if (nestingLevel > MAX_OPEN_PARENS) {
                    throw new JSQLParserException("Skipping sql that exceeds max nesting level: max=" + MAX_OPEN_PARENS + " level=" + nestingLevel);
                }

                int closedParens = getClosedParens(query);
                if (nestingLevel - closedParens > 1) {
                    builder.setTruncated(true);
                    throw new JSQLParserException("Unclosed parens: " + (nestingLevel - closedParens));
                }

                long startNormalization = System.nanoTime();
                NormalizedQueryDigest.DigestedQuery digested =
                    normalizedQueryDigest.digestedQuery(query, row.getSchemaName());
                builder.setNormalizedDigest(digested.digest());

                String display = sqlFormatter.format(digested.normalizedSql());
                builder.setQuery(display);
                builder.setRepeatedBlocks(RepeatLocator.locate(display, digested.repeats()).stream()
                    .map(located -> new RepeatedBlock(located.start(), located.end(), located.count()))
                    .toList());

                if (repository.attemptMerge(digested.digest(), builder)) {
                    return;
                }

                Duration normalizedParseTime = Duration.ofNanos(System.nanoTime() - startNormalization);
                if (MAX_PARSE.compareTo(normalizedParseTime) < 0) {
                    log.info("Parse time exceeded " + MAX_PARSE.toSeconds() + " second, aborting analysis");
                    builder.setAnalysisFailureReason("parse time exceeded " + MAX_PARSE.toSeconds() + " seconds");
                } else {
                    sqlAnalysisService.analyzeSql(query, builder, schema);
                }
            } catch (JSQLParserException e) {
                if (containsEof(e)) {
                    builder.setTruncated(true);
                } else {
                    builder.setAnalysisFailureReason(getRootCauseMessage(e));
                }

                builder.setTables(tables);
                flagUnknownTables(builder, unknownTables);

                if (traceErrors) {
                    log.info("Parse failed {}", row.getDigest(), e);
                }

                builder.setAnalysisFailed(true);
            }
        }

        markDml(builder);
        SqlProfile sqlProfile = builder.completeBuild(row.getSchemaName());

        repository.addToIndex(sqlProfile);
    }

    private static void markDml(SqlProfile.Builder builder) {
        builder.setIsDml(QueryKind.of(builder.getType(), builder.getRuntimeMetrics()).isWrite());
    }

    static void copyAnalysis(SqlProfile prior, SqlProfile.Builder builder) {
        builder.setQuery(prior.getQuery())
            .setTables(prior.getTables())
            .setWriteTables(prior.getWriteTables())
            .setType(prior.getType())
            .setJoinColumns(prior.getJoinColumns())
            .setCriteriaColumns(prior.getCriteriaColumns())
            .setSelectColumns(prior.getSelectColumns())
            .setGroupByColumns(prior.getGroupByColumns())
            .setIndexHints(prior.getIndexHints())
            .setOrderByColumns(prior.getOrderByColumns())
            .setCriteriaComparisons(prior.getCriteriaComparisons())
            .setGeneratedSource(prior.getGeneratedSource())
            .setGeneratorCandidates(prior.getGeneratorCandidates())
            .setTruncated(prior.getTruncated())
            .setAnalysisFailed(prior.getAnalysisFailed())
            .setAnalysisSkipped(prior.getAnalysisSkipped())
            .setNormalizedDigest(prior.getNormalizedDigest())
            .setSchemaName(prior.getSchemaName())
            .setSchemas(prior.getSchemas())
            .setMismatchedJoins(prior.getMismatchedJoins())
            .setAnalysisFailureReason(prior.getAnalysisFailureReason())
            .setModifiedForAnalysis(prior.getModifiedForAnalysis())
            .setIsMultiSchema(prior.getIsMultiSchema())
            .setRepeatedBlocks(prior.getRepeatedBlocks())
            .setAnalyzerVersion(prior.getAnalyzerVersion())
            .setSessionSchema(prior.getSessionSchema());
    }

    static void flagUnknownTables(SqlProfile.Builder builder, List<TableReference> unknownTables) {
        if (unknownTables.isEmpty() || builder.getAnalysisFailureReason() != null) {
            return;
        }
        builder.setAnalysisFailureReason(RegexTableExtractor.UNKNOWN_TABLE_REASON + " "
            + unknownTables.stream().map(TableReference::getFullName).collect(Collectors.joining(", ")));
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

    private int countChar(char needle, String haystack) {
        int openParens = 0;
        int start = 0;
        int index = haystack.indexOf(needle, start);
        while (index != -1) {
            openParens++;
            start = index + 1;
            index = haystack.indexOf(needle, start);
        }
        return openParens;
    }

    private int getOpenParens(String query) {
        return countChar('(', query);
    }

    private int getClosedParens(String query) {
        return countChar(')', query);
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

    static String firstToken(String sql) {
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
        private final ListMultimap<String, SqlProfile> digestAnalyzedSqlRepository = Multimaps.synchronizedListMultimap(ArrayListMultimap.create());
        private List<SqlProfile> sqlProfiles = new ArrayList<>();
        private final ListMultimap<TableReference, SqlProfile> tableNameAnalyzedSqlRepository = ArrayListMultimap.create();
        private final List<SqlProfile> parquetRows = new ArrayList<>();

        void reused() {
            statsBuilder.reused();
        }

        void pending() {
            statsBuilder.pending();
        }

        public SqlRepositoryBuilder(SqlSource source) {
            this.source = source;
        }

        boolean attemptMerge(String normalizedDigest, SqlProfile.Builder builder) {

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
                .setSelectColumns(existingEntry.getSelectColumns())
                .setGroupByColumns(existingEntry.getGroupByColumns())
                .setIndexHints(existingEntry.getIndexHints())
                .setOrderByColumns(existingEntry.getOrderByColumns())
                .setCriteriaComparisons(existingEntry.getCriteriaComparisons())
                .setAnalysisFailureReason(existingEntry.getAnalysisFailureReason())
                .setIsDml(existingEntry.getIsDml())
                .setMismatchedJoins(existingEntry.getMismatchedJoins())
                .setTruncated(existingEntry.getTruncated())
                .setModifiedForAnalysis(existingEntry.getModifiedForAnalysis())
                .setGeneratedSource(existingEntry.getGeneratedSource())
                .setGeneratorCandidates(existingEntry.getGeneratorCandidates())
                .setTables(existingEntry.getTables())
                .setWriteTables(existingEntry.getWriteTables())
                .setType(existingEntry.getType());

            SqlProfile sqlProfile = builder.completeBuild(existingEntry.getSchemaName());
            addToIndex(sqlProfile);

            return true;
        }

        void addToIndex(SqlProfile sqlProfile) {
            synchronized (lock) {
                parquetRows.add(sqlProfile);
            }
            index(sqlProfile);
        }

        private void index(SqlProfile sqlProfile) {
            synchronized (lock) {
                if (sqlProfile.getNormalizedDigest() != null) {
                    digestAnalyzedSqlRepository.put(sqlProfile.getNormalizedDigest(), sqlProfile);
                }
                this.sqlProfiles.add(sqlProfile);
            }

            reportStatistics(sqlProfile);
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

        void writeParquet(ParquetSink sink) {
            synchronized (lock) {
                sink.write(capture(), parquetRows);
            }
        }

        CaptureContext capture() {
            CaptureContext known = capture;
            if (known == null) {
                known = captureContext();
                capture = known;
            }
            return known;
        }

        private CaptureContext capture;

        private CaptureContext captureContext() {
            LocalDateTime from = null;
            LocalDateTime to = null;
            for (SqlProfile profile : parquetRows) {
                RuntimeMetrics metrics = profile.getRuntimeMetrics();
                if (metrics == null) {
                    continue;
                }
                from = earliest(from, metrics.getFirstSeen());
                to = latest(to, metrics.getLastSeen());
            }
            Instant capturedAt = null;
            if (source != null) {
                capturedAt = source.getCapturedAt() != null ? source.getCapturedAt() : source.getCreatedOn();
            }
            return new CaptureContext(
                source == null ? null : source.getCaptureOrigin(),
                source == null ? null : source.getSourceRef(),
                capturedAt,
                parquetRows.size(),
                from, to,
                source == null ? Map.of() : source.getCaptureServerContext());
        }

        private static LocalDateTime earliest(LocalDateTime current, LocalDateTime seen) {
            return seen == null ? current : (current == null || seen.isBefore(current) ? seen : current);
        }

        private static LocalDateTime latest(LocalDateTime current, LocalDateTime seen) {
            return seen == null ? current : (current == null || seen.isAfter(current) ? seen : current);
        }

        List<SqlProfile> profilesForTest() {
            synchronized (lock) {
                return List.copyOf(sqlProfiles);
            }
        }

        int parquetRowCount() {
            synchronized (lock) {
                return parquetRows.size();
            }
        }

        public SqlRepository build() {
            return new AnalyzedSqlRepository(source, statsBuilder.build(), sqlProfiles, capture());
        }
    }
}
