package com.github.safetytrick.sqlexplorer.examples;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.uuid.Generators;
import com.github.safetytrick.sqlexplorer.analysis.SqlAnalysisService;
import com.github.safetytrick.sqlexplorer.analysis.parser.NormalizedQueryDigest;
import com.github.safetytrick.sqlexplorer.auth.UserService;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.environment.EnvironmentContext;
import com.github.safetytrick.sqlexplorer.schema.SqlSchemaRepository;
import com.github.safetytrick.sqlexplorer.schema.TableStructure;
import net.sf.jsqlparser.JSQLParserException;
import org.springframework.stereotype.Service;

/**
 * Turns a submitted statement into a stored {@link QueryExample}.
 *
 * <p>The whole point of this class is that the caller supplies a statement and
 * <em>nothing else that matters</em>. The digest, the environment and — when
 * not stated — the session schema are all derived here, because each of them is
 * a claim the server can check and the caller cannot be trusted to get right.
 * A digest taken from a request would attach any statement to any group.
 */
@Service
class QueryExampleService {

    private final QueryExampleRepository repository;
    private final NormalizedQueryDigest normalizedQueryDigest;
    private final SqlAnalysisService sqlAnalysisService;
    private final SqlSchemaRepository schemaRepository;
    private final EnvironmentContext environmentContext;
    private final UserService userService;

    QueryExampleService(QueryExampleRepository repository,
                        NormalizedQueryDigest normalizedQueryDigest,
                        SqlAnalysisService sqlAnalysisService,
                        SqlSchemaRepository schemaRepository,
                        EnvironmentContext environmentContext,
                        UserService userService) {
        this.repository = repository;
        this.normalizedQueryDigest = normalizedQueryDigest;
        this.sqlAnalysisService = sqlAnalysisService;
        this.schemaRepository = schemaRepository;
        this.environmentContext = environmentContext;
        this.userService = userService;
    }

    /**
     * Why a submission could not be stored. Each of these is a genuinely
     * different situation for the caller and they are deliberately not collapsed
     * into one "bad request" — the fix differs.
     */
    enum Failure {
        /** jsqlparser could not read the statement, so no digest exists to derive. */
        UNPARSEABLE,
        /** The statement parsed, but an analyzer threw and the schema is unknowable. */
        ANALYSIS_FAILED,
        /** The statement's tables live in more than one schema. Candidates are listed. */
        AMBIGUOUS_SCHEMA,
        /** No schema in the active environment contains the statement's tables. */
        NO_MATCHING_SCHEMA,
        /** The active environment has no schema loaded, so nothing can be inferred. */
        SCHEMA_UNAVAILABLE
    }

    /**
     * The outcome of a submission: either a stored example, or a failure with
     * enough detail to act on. {@code candidateSchemas} is populated for
     * {@link Failure#AMBIGUOUS_SCHEMA} so the caller can pick one and resubmit.
     */
    record Result(QueryExample stored, Failure failure, String detail, List<String> candidateSchemas) {

        static Result ok(QueryExample stored) {
            return new Result(stored, null, null, List.of());
        }

        static Result failed(Failure failure, String detail, List<String> candidates) {
            return new Result(null, failure, detail, candidates);
        }

        boolean succeeded() {
            return stored != null;
        }
    }

    Result create(String queryText, String sessionSchema, String description) {
        SqlProfile.Builder builder = SqlProfile.newBuilder();
        // supplied wins: UnknownSchemaAnalyzer only infers when schemaName is null,
        // so setting it here skips inference rather than competing with it
        boolean supplied = sessionSchema != null && !sessionSchema.isBlank();
        if (supplied) {
            builder.setSchemaName(sessionSchema);
        }

        try {
            sqlAnalysisService.analyzeSql(queryText, builder, schemaRepository);
        } catch (JSQLParserException e) {
            return Result.failed(Failure.UNPARSEABLE, rootMessage(e), List.of());
        } catch (RuntimeException e) {
            // schemaRepository access is lazy; a server that cannot be reached
            // surfaces here rather than as a missing schema
            return Result.failed(Failure.SCHEMA_UNAVAILABLE, rootMessage(e), List.of());
        }

        String resolved;
        if (supplied) {
            resolved = sessionSchema;
        } else {
            // Inference cannot be trusted to report ambiguity: when a table name
            // exists in several schemas, findSchemaForTablesAndColumns returns
            // whichever schema first satisfies the columns rather than null. That
            // is fine for the corpus, where the session schema usually arrives
            // from Performance Schema and inference is a fallback — it is not
            // fine here, where an arbitrary pick silently attaches the example to
            // possibly the wrong group. So ambiguity is detected independently.
            List<String> candidates = candidateSchemas(builder.getTables());
            if (candidates.size() > 1) {
                return Result.failed(Failure.AMBIGUOUS_SCHEMA,
                    "more than one schema contains the tables this statement references; resubmit with one of them",
                    candidates);
            }
            resolved = builder.hasSchemaName() ? builder.getSchemaName() : null;
            if (resolved == null && candidates.size() == 1) {
                resolved = candidates.getFirst();
            }
        }
        if (resolved == null) {
            return inferenceFailure(builder);
        }

        String digest;
        try {
            digest = normalizedQueryDigest.normalizedDigest(queryText, resolved);
        } catch (JSQLParserException e) {
            return Result.failed(Failure.UNPARSEABLE, rootMessage(e), List.of());
        }

        QueryExample example = new QueryExample();
        example.setId(Generators.timeBasedEpochGenerator().generate());
        example.setQueryText(queryText);
        example.setNormalizedDigest(digest);
        example.setSessionSchema(resolved);
        example.setSchemaInferred(!supplied);
        example.setDescription(description);
        example.setEnvironmentName(environmentContext.current().getName());
        example.setAuthor(userService.currentUser());

        return Result.ok(repository.save(example));
    }

    List<QueryExample> forDigest(String digest, org.springframework.data.domain.Pageable pageable) {
        return repository.findByNormalizedDigestOrderByIdDesc(digest, pageable);
    }

    long countForDigest(String digest) {
        return repository.countByNormalizedDigest(digest);
    }

    /**
     * Inference produced nothing. {@code findSchemaForTablesAndColumns} returns
     * null for "ambiguous" and for "no such tables" alike — it even carries a
     * {@code // todo, why doesn't this throw an exception?} — so the two are
     * separated here by enumerating candidates directly.
     *
     * <p>An analyzer failure is checked first because {@code analyzeSql} catches
     * every analyzer exception and records a reason rather than propagating it.
     * Without this check a statement whose analysis blew up would be reported as
     * an ambiguous schema, which is a confusing lie: the schema was never the
     * problem.
     */
    private Result inferenceFailure(SqlProfile.Builder builder) {
        // Table names first, analyzer failure second. analyzeSql catches every
        // analyzer exception and records a reason rather than propagating, and a
        // statement naming an unknown table reliably trips one (see sqlx-as7).
        // Reporting that as ANALYSIS_FAILED would bury the useful answer: we know
        // the tables, and no schema has them.
        List<TableReference> tables = builder.getTables();
        if (tables != null && !tables.isEmpty()) {
            return Result.failed(Failure.NO_MATCHING_SCHEMA,
                "no schema in the active environment contains the tables this statement references",
                List.of());
        }
        if (builder.hasAnalysisFailureReason() && builder.getAnalysisFailureReason() != null) {
            return Result.failed(Failure.ANALYSIS_FAILED, builder.getAnalysisFailureReason(), List.of());
        }
        return Result.failed(Failure.NO_MATCHING_SCHEMA,
            "no tables could be resolved from this statement", List.of());
    }

    /**
     * Every schema that contains a table this statement names. Insertion-ordered
     * and de-duplicated so the list a caller is shown is stable between two
     * identical requests — a set that reordered itself would make the 4xx look
     * non-deterministic.
     */
    private List<String> candidateSchemas(List<TableReference> tables) {
        if (tables == null) {
            return List.of();
        }
        Set<String> schemas = new LinkedHashSet<>();
        for (TableReference table : tables) {
            // A qualified reference names its own schema; looking it up by name
            // would find same-named tables elsewhere and invent an ambiguity that
            // the statement does not actually have.
            if (table.getSchemaName() != null && !table.getSchemaName().isBlank()) {
                schemas.add(table.getSchemaName());
                continue;
            }
            List<TableStructure> matches;
            try {
                matches = schemaRepository.getTablesByName(table);
            } catch (RuntimeException e) {
                return List.of();
            }
            if (matches == null) {
                continue;
            }
            matches.stream()
                .map(TableStructure::schema)
                .filter(schema -> schema != null && !schema.isBlank())
                .forEach(schemas::add);
        }
        return new ArrayList<>(schemas);
    }

    private static String rootMessage(Throwable e) {
        String message = e.toString();
        while (e != null) {
            if (e.getMessage() != null) {
                message = e.getMessage();
            }
            e = e.getCause();
        }
        return message;
    }
}
