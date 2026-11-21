package com.github.safetytrick.sqlexplorer.examples;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.uuid.Generators;
import com.github.safetytrick.sqlexplorer.analysis.SqlAnalysisService;
import com.github.safetytrick.sqlexplorer.analysis.parser.NormalizedQueryDigest;
import com.github.safetytrick.sqlexplorer.analysis.parser.QueryParameterizer;
import com.github.safetytrick.sqlexplorer.evidence.SqlRepositoryService;
import com.github.safetytrick.sqlexplorer.auth.UserService;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.environment.EnvironmentContext;
import com.github.safetytrick.sqlexplorer.schema.SqlSchemaRepository;
import com.github.safetytrick.sqlexplorer.schema.TableStructure;
import net.sf.jsqlparser.JSQLParserException;
import org.springframework.stereotype.Service;

@Service
class QueryExampleService {

    private final QueryExampleRepository repository;
    private final NormalizedQueryDigest normalizedQueryDigest;
    private final QueryParameterizer queryParameterizer;
    private final SqlRepositoryService sqlRepositoryService;
    private final SqlAnalysisService sqlAnalysisService;
    private final SqlSchemaRepository schemaRepository;
    private final EnvironmentContext environmentContext;
    private final UserService userService;

    QueryExampleService(QueryExampleRepository repository,
                        NormalizedQueryDigest normalizedQueryDigest,
                        QueryParameterizer queryParameterizer,
                        SqlRepositoryService sqlRepositoryService,
                        SqlAnalysisService sqlAnalysisService,
                        SqlSchemaRepository schemaRepository,
                        EnvironmentContext environmentContext,
                        UserService userService) {
        this.repository = repository;
        this.normalizedQueryDigest = normalizedQueryDigest;
        this.queryParameterizer = queryParameterizer;
        this.sqlRepositoryService = sqlRepositoryService;
        this.sqlAnalysisService = sqlAnalysisService;
        this.schemaRepository = schemaRepository;
        this.environmentContext = environmentContext;
        this.userService = userService;
    }

    enum Failure {
        UNPARSEABLE,
        ANALYSIS_FAILED,
        AMBIGUOUS_SCHEMA,
        NO_MATCHING_SCHEMA,
        SCHEMA_UNAVAILABLE,
        NO_MATCHING_PROFILE
    }

    record Result(QueryExample stored, Failure failure, String detail, List<String> candidateSchemas,
                  boolean matchedAProfile) {

        static Result ok(QueryExample stored, boolean matchedAProfile) {
            return new Result(stored, null, null, List.of(), matchedAProfile);
        }

        static Result failed(Failure failure, String detail, List<String> candidates) {
            return new Result(null, failure, detail, candidates, false);
        }

        boolean succeeded() {
            return stored != null;
        }
    }

    Result create(String queryText, String sessionSchema, String description) {
        SqlProfile.Builder builder = SqlProfile.newBuilder();
        boolean supplied = sessionSchema != null && !sessionSchema.isBlank();
        if (supplied) {
            builder.setSchemaName(sessionSchema);
        }

        try {
            sqlAnalysisService.analyzeSql(queryText, builder, schemaRepository);
        } catch (JSQLParserException e) {
            return Result.failed(Failure.UNPARSEABLE, rootMessage(e), List.of());
        } catch (RuntimeException e) {
            return Result.failed(Failure.SCHEMA_UNAVAILABLE, rootMessage(e), List.of());
        }

        String resolved;
        if (supplied) {
            resolved = sessionSchema;
        } else {
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
            // The corpus holds MySQL's digest_text, whose literals are already
            // `?`. An example is the one thing that arrives with its values, so
            // it is reduced the same way before hashing — otherwise it keys to a
            // digest no profile can carry.
            digest = normalizedQueryDigest.normalizedDigest(queryParameterizer.parameterize(queryText), resolved);
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

        return Result.ok(repository.save(example), carriedByALoadedProfile(digest));
    }

    private boolean carriedByALoadedProfile(String digest) {
        try {
            return sqlRepositoryService.getByDigest(digest) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    List<QueryExample> forDigest(String digest, org.springframework.data.domain.Pageable pageable) {
        return repository.findByNormalizedDigestOrderByIdDesc(digest, pageable);
    }

    long countForDigest(String digest) {
        return repository.countByNormalizedDigest(digest);
    }

    private Result inferenceFailure(SqlProfile.Builder builder) {
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

    private List<String> candidateSchemas(List<TableReference> tables) {
        if (tables == null) {
            return List.of();
        }
        Set<String> schemas = new LinkedHashSet<>();
        for (TableReference table : tables) {
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
