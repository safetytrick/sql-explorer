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

    /**
     * Statements not worth parsing: they carry no columns, no joins and no
     * criteria, so the analyzer has nothing to take from them.
     *
     * <p>Membership is load-bearing beyond the parsing cost. Anything absent
     * here that also fails to parse lands in the parse-failures report and
     * behind the workbench's {@code analFail} filter, both of which exist to
     * answer "what should have been analysed and was not". Measured 2026-08-29,
     * 25 of the corpus's 35 parse failures were {@code SAVEPOINT},
     * {@code RELEASE SAVEPOINT} and {@code PURGE BINARY LOGS} — noise
     * outnumbering the real failures 3:1 and burying them (sqlx-96y).
     *
     * <p>{@code ROLLBACK} covers {@code ROLLBACK TO SAVEPOINT} already, since
     * the match is on the first token alone.
     */
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

    /** Introspection, not query load — see the note where its tables are dropped. */
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
     */
    @Timed("performance_schema_import")
    /** Every row analysed, nothing reused — the behaviour before analysis reuse existed. */
    public SqlRepository fromPerformanceSchema(SqlSource source, Stream<SqlInput> inputStream, SqlSchemaRepository schema) {
        return fromPerformanceSchema(source, inputStream, schema, AnalysisContext.ANALYSE_EVERYTHING);
    }

    /**
     * What a load knows about analysing a source's rows (sqlx-sqy.5).
     *
     * @param reuse         earlier analysis, reused for any row whose digest and
     *                      default schema it holds, with no parsing
     * @param schemaCurrent whether the source's cluster schema was confirmed
     *                      current. When it was not, a row with no earlier
     *                      analysis is left out and counted pending: nothing is
     *                      analysed against a schema that may have changed
     */
    public record AnalysisContext(AnalysisReuse reuse, boolean schemaCurrent) {
        public static final AnalysisContext ANALYSE_EVERYTHING = new AnalysisContext(AnalysisReuse.NONE, true);
    }

    public SqlRepository fromPerformanceSchema(SqlSource source, Stream<SqlInput> inputStream, SqlSchemaRepository schema,
                                               AnalysisContext context) {
        SqlRepositoryBuilder repository = new SqlRepositoryBuilder(source);
        // inputStream may be a parallel stream (e.g. XStatementAnalysisSqlSource),
        // so collect the submitted futures rather than adding to a shared
        // ArrayList from the stream action — concurrent add() corrupts the list.
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

        // Written before mergeByDigest, and that ordering is still load-bearing
        // for a different reason than it used to be: parquet writes through the
        // Avro accessors, which MergedSqlProfile does not override, so writing
        // after the wrap would store each group's representative once and lose
        // the members. Every profile gets a row; the reader rebuilds the groups
        // by normalized digest exactly as the next line does (sqlx-4ww).
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
            // a few entries in the mysql export are null
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
        // the operation, read past any CTEs; refined by the parse if there is one
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

        // The file a row came from, when it came from a file at all. Null for a
        // live source like x-statement-analysis, which is not a file.
        String sourceFileName = toProcess.source() instanceof AbstractTsvSqlSource tsvSource
            ? tsvSource.getResource().getFilename() : null;

        builder.setLineNumber(lineNumber)
            // A WITHIN-LOAD HANDLE, NOT AN IDENTITY. The source's toString
            // embeds the import instant, so this changes on every import — and
            // that is fine, because nothing resolves by it: the repositories
            // index by digest, the frontend uses it only as a list track-by
            // within one page, and nothing persists it. What it buys is
            // knowing which source and which row a profile came from when
            // reading a log or debugging an import, which a digest cannot say
            // (sqlx-40c).
            .setId(toProcess.source() + "-" + lineNumber)
            .setSourceFileName(sourceFileName)
            // Seeded here so the merge aggregation has something to aggregate.
            // `MergedSqlProfile.getSourcedFiles()` flat-maps this across a merge
            // group — which is the useful fact, "which files contributed to this
            // query" — and it has always flat-mapped empty lists, because the
            // singular `sourceFileName` was never carried into the plural. One
            // element per profile is what makes the group answer real.
            .setSourcedFiles(sourceFileName == null ? List.of() : List.of(sourceFileName))
            .setQuery(query)
            .setType(textType)
            .setSchemaName(row.getSchemaName() != null ? row.getSchemaName() : "dual")
            .setRuntimeMetrics(runtimeMetrics)
            .setQueryActivityStatus(status)
            .setIsDml(QueryKind.of(textType, row.getRowsAffected()).isWrite());

        if (row.getErrors() == row.getExecuted()) {
            //if the query always fails we might not be able to parse it and don't really care about it anyway
            return;
        }

        // Analysed before, by this version of the analysis: take that, and
        // only the row's own facts — its metrics, its place in this source —
        // are new. No parsing, and no need for a current schema (sqlx-sqy.5).
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
        // The regex is the only reader of anything in `ignoredFirstTokens`, so
        // nothing else corroborates its guessed positions. The schema can: a
        // name it does not know means the pattern matched something that is not
        // a table reference. DDL positions are excluded — they carry their own
        // object-type keyword, so there is nothing left to corroborate.
        List<TableReference> unknownTables =
            tableExtractor.unknownTables(row.getSchemaName(), query, schema);

        int nestingLevel = getOpenParens(query);

        if (ignoredFirstTokens.contains(firstToken) || ignoredSchemas.ignoreAll(tables)) {
            // A SHOW names tables it is *asking about*, never tables it uses, so
            // its names are not evidence of anything and are recorded as none.
            //
            // They were worse than useless. `SHOW CREATE TABLE `x`` reaches only
            // the regex extractor — SHOW is ignored here, so nothing parses it —
            // and the extractor's DDL patterns match the CREATE TABLE *inside*
            // it. Nothing corroborates the name either, because unknownTables
            // skips DDL positions on the grounds that the object-type keyword
            // proves the name is a table. True of `CREATE TABLE x`; false of
            // `SHOW CREATE TABLE x`, which introspects a table that may be long
            // gone. That put 162 tables into the schema tree that do not exist —
            // 19% of it — all of them materialised-view rebuild artifacts named
            // `<view>_<hex timestamp>` (sqlx-1mm).
            //
            // It also quietly inflated real tables: 13 that no query touches
            // carried a query count of 1-3 because a SHOW had named them.
            //
            // Rejected: matching the `_<13 hex>` suffix. That describes this
            // corpus's artifacts, would miss the next tool's naming scheme, and
            // risks a real table that happens to look like one. The statement
            // kind is the actual cause.
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

                // optimistic merging decreases the amount of analysis we do but doesn't help
                // much with anything else, most of the file processing time is spent in the sql parser.
                long startNormalization = System.nanoTime();
                // Scoped by the session schema: the digest is what decides
                // which rows merge into one profile, and the same unqualified
                // text run against two schemas is two statements.
                NormalizedQueryDigest.DigestedQuery digested =
                    normalizedQueryDigest.digestedQuery(query, row.getSchemaName());
                builder.setNormalizedDigest(digested.digest());

                // display the normalized form (raw formatted text set above
                // remains the fallback for sql that doesn't parse), with the
                // collapsed repeats located so the UI can badge "×N".
                // Done before the merge attempt, not after: a merged child's
                // own repeat counts are the point of the merge-group view, and
                // they only line up against the representative's text because
                // the whole group normalizes to the same form.
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

    /**
     * DML is what {@link QueryKind} calls a write, asked once the type is
     * final: a parse can retype a WITH statement, and reuse brings the type
     * an earlier parse decided. isDml is the row's own fact, so it is not
     * copied by reuse but derived again here.
     */
    private static void markDml(SqlProfile.Builder builder) {
        builder.setIsDml(QueryKind.of(builder.getType(), builder.getRuntimeMetrics()).isWrite());
    }

    /**
     * Everything an analysis decided about a statement, from an earlier
     * profile of it. Not the row's own facts — id, line, source file, runtime
     * metrics, activity, isDml — which the caller has already set from the
     * row, nor merge membership, which this load decides.
     */
    static void copyAnalysis(SqlProfile prior, SqlProfile.Builder builder) {
        builder.setQuery(prior.getQuery())
            .setTables(prior.getTables())
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

    /**
     * Record extracted names the schema does not know, so a pre-scan that
     * misreads a statement fails loudly instead of quietly seeding the schema
     * tree with tables that do not exist.
     *
     * <p>Only when no reason is set yet. On the parse-failure path the parser's
     * own message is the better explanation and it is written first; overwriting
     * it would trade a cause for a symptom. That ordering matters because
     * `analysisFailureReason` is a single field with several writers and the
     * last one wins — see the failure-capture item in WORK_TODO.
     *
     * <p>`complete()` derives `analysisFailed` from the reason, so setting this
     * marks the profile failed without a second flag to keep in step.
     */
    // package-private and static: no instance state, and the only-if-absent
    // rule below is worth driving directly from a test.
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
        // only by normalized digest
        private final ListMultimap<String, SqlProfile> digestAnalyzedSqlRepository = Multimaps.synchronizedListMultimap(ArrayListMultimap.create());
        private List<SqlProfile> sqlProfiles = new ArrayList<>();
        private final ListMultimap<TableReference, SqlProfile> tableNameAnalyzedSqlRepository = ArrayListMultimap.create();
        /**
         * Every profile, one row each — including merged children.
         *
         * <p>MERGING IS NOT PERSISTED. It is a function of the normalized
         * digest, so a reader recreates it by grouping the rows exactly as a
         * fresh import does. This used to hold only representatives, with each
         * child folded into its representative's {@code mergedChildren}; that
         * made the group durable but not reconstructable, and a corpus rebuilt
         * from a capture silently lost every group's aggregate metrics —
         * measured at 32% understated on one live query (sqlx-4ww).
         *
         * <p>No double counting follows: each row is a distinct MySQL digest
         * with its own counter, which is precisely what merging sums.
         */
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

        // package-private: the merge decision is the seam MergeGroupPersistenceTest
        // drives directly, because through the executor which row wins the race
        // to be the representative is not deterministic.
        boolean attemptMerge(String normalizedDigest, SqlProfile.Builder builder) {

            if (!digestAnalyzedSqlRepository.containsKey(normalizedDigest)) {
                statsBuilder.notMerged();
                return false;
            }

            statsBuilder.merged();

            SqlProfile existingEntry = digestAnalyzedSqlRepository.get(normalizedDigest).getFirst();
            // Everything the analyzers derived, copied wholesale. The child is
            // never parsed — that is the point of merging — so any field an
            // analyzer would have filled is not "false"/"empty" on it, it is
            // unknown, and the representative's value is the group's answer.
            // Listing the fields one at a time is the same shape that bit
            // MergedSqlProfile: what the list omits silently keeps the builder
            // default. selectColumns, groupByColumns, generatedSource and
            // modifiedForAnalysis were the four omissions — analyzer-derived
            // like the rest, and not re-derivable in complete(), so a child
            // that won the race to be the group's first() reported none of
            // them. Fields derived in complete() (schemas, isMultiSchema,
            // digests) are deliberately absent: they follow from tables, which
            // is copied.
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
                .setType(existingEntry.getType());

            SqlProfile sqlProfile = builder.completeBuild(existingEntry.getSchemaName());
            // The child is indexed like any other profile and gets a row of its
            // own. It used to be folded onto the representative instead, which
            // made the merge group a persisted fact that a rebuilt corpus could
            // not recreate — see writeParquet (sqlx-4ww).
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
            // Both publications under one lock, because they are one fact.
            //
            // These used to be separate: the digest map was written first and
            // outside the lock, the list second and inside it. A profile was
            // therefore findable as a merge representative before it existed in
            // the list, so a child could reach the list ahead of the
            // representative it merged into — and `mergeByDigest` groups in list
            // order, which made `MergedSqlProfile.first()` a race.
            //
            // Swapping the order is not a fix, it is a different bug: a child
            // arriving in the window would find no key, decline to merge, and
            // become its own profile. The window is what has to go, not its
            // direction.
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

        /**
         * Write one parquet row per representative, after every child has
         * been folded in. Writing per row during processing (as this used to)
         * meant a representative was serialized before its children existed,
         * so merge groups never survived a reload.
         */
        void writeParquet(ParquetSink sink) {
            synchronized (lock) {
                // One call, one capture, one file. The context travels with the
                // rows rather than being declared first: two sources of one
                // environment import in parallel (loadFromSqlSources uses a
                // parallel stream), and a sink that held "the current context"
                // between calls would race them into each other's files
                // (sqlx-fx2).
                sink.write(capture(), parquetRows);
            }
        }

        /**
         * What this file records, for the reader that opens it later.
         *
         * <p>The observed window is taken from the rows themselves rather than
         * from the clock. Performance Schema accumulates until the server
         * restarts, so consecutive samples of one source normally overlap;
         * when a later sample's earliest {@code firstSeen} falls after an
         * earlier one's latest {@code lastSeen}, the digest table was reset in
         * between and that interval went unrecorded. Stating the window per
         * file is what makes that detectable (sqlx-sy5).
         *
         * <p>The server context comes from the source, which read it when it
         * read the rows — over the same connection for a direct source, and
         * from what the uploader supplied for an export. Empty means unknown,
         * and it must stay that way: filling it in later from a live
         * connection would attribute one server's settings to another's data.
         */
        /**
         * Computed once, over {@code parquetRows}, and kept.
         *
         * <p>The written file and the coverage API must answer the same
         * question with the same numbers. Recomputing later would read the
         * merged representatives instead of the rows that were written — a
         * different population, so the observed window could legitimately
         * differ between the two and neither would be wrong (sqlx-n01).
         */
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
            // Prefer the capture instant over the source's creation time. For an
            // uploaded export the two can be months apart, and it is the capture
            // that the coverage signals are about; creation time is only a
            // fallback for sources that never knew (sqlx-sy5).
            Instant capturedAt = null;
            if (source != null) {
                capturedAt = source.getCapturedAt() != null ? source.getCapturedAt() : source.getCreatedOn();
            }
            return new CaptureContext(
                // How the rows reached us, not what kind of file this becomes.
                // Stamping PARQUET_COPY here reported a direct read off a live
                // server as a two-hop copy, which understated exactly the case
                // where the settings DO describe the data (sqlx-n01).
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

        /** The indexed profiles in index order, for the ordering-invariant test. */
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
