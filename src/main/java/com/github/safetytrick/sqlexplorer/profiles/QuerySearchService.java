package com.github.safetytrick.sqlexplorer.profiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

import com.github.safetytrick.sqlexplorer.analysis.StatementTypes;
import com.github.safetytrick.sqlexplorer.avro.ColumnReference;
import com.github.safetytrick.sqlexplorer.avro.IndexHint;
import com.github.safetytrick.sqlexplorer.avro.QueryActivityStatus;
import com.github.safetytrick.sqlexplorer.avro.RuntimeMetrics;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.commentary.Tag;
import com.github.safetytrick.sqlexplorer.commentary.TagsService;
import com.github.safetytrick.sqlexplorer.examples.QueryExampleLookup;
import com.github.safetytrick.sqlexplorer.evidence.SqlRepositoryService;
import com.github.safetytrick.sqlexplorer.profiles.QuerySearchRequest.DateRange;
import com.github.safetytrick.sqlexplorer.profiles.QuerySearchRequest.Flag;
import com.github.safetytrick.sqlexplorer.profiles.QuerySearchRequest.Range;
import com.github.safetytrick.sqlexplorer.profiles.QuerySearchResult.QueryHit;
import org.springframework.stereotype.Service;

@Service
public class QuerySearchService {

    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 1000;

    public static final Set<String> EXPLICIT_TYPES = Set.of(
        "SELECT", "INSERT", "UPDATE", "DELETE", "CREATE", "ALTER", "DROP", "SHOW", "UNKNOWN");
    public static final String OTHER_TYPE = "OTHER";

    private static final String QUERY_TAG_TYPE = "query";
    private static final String TABLE_TAG_TYPE = "table";
    private static final double PICOS_PER_MILLI = 1_000_000_000d;

    private final SqlRepositoryService sqlRepositoryService;
    private final TagsService tagsService;
    private final QueryExampleLookup queryExampleLookup;

    public QuerySearchService(SqlRepositoryService sqlRepositoryService, TagsService tagsService,
                              QueryExampleLookup queryExampleLookup) {
        this.sqlRepositoryService = sqlRepositoryService;
        this.tagsService = tagsService;
        this.queryExampleLookup = queryExampleLookup;
    }

    public QuerySearchResult search(QuerySearchRequest request) {
        QuerySearchRequest criteria = request == null ? QuerySearchRequest.UNCONSTRAINED : request;
        Sweep sweep = sweep(criteria);
        List<QueryHit> matches = new ArrayList<>(sweep.matches());
        matches.sort(comparator(criteria.sort()));

        int offset = Math.max(criteria.offset() == null ? 0 : criteria.offset(), 0);
        int limit = Math.min(criteria.limit() == null || criteria.limit() < 1 ? DEFAULT_LIMIT : criteria.limit(), MAX_LIMIT);
        int from = Math.min(offset, matches.size());
        int to = Math.min(from + limit, matches.size());
        return new QuerySearchResult(matches.size(), List.copyOf(matches.subList(from, to)),
            new QuerySearchResult.Facets(sweep.tableFacets(), sweep.schemaFacets(), sweep.sourceFacets()));
    }

    public List<SqlProfile> matchingProfiles(QuerySearchRequest request) {
        return sweep(request == null ? QuerySearchRequest.UNCONSTRAINED : request).matches().stream()
            .map(QueryHit::profile)
            .toList();
    }

    private Sweep sweep(QuerySearchRequest criteria) {
        Set<String> digestsWithExamples = Boolean.TRUE.equals(criteria.hasExamples())
            ? queryExampleLookup.digestsWithExamples()
            : Set.of();
        Map<String, List<Tag>> tagsByQueryId = queryTagsById();
        Set<String> taggedTables = taggedTables(criteria.tableTags());
        Set<String> excludedTaggedTables = taggedTables(criteria.excludeTableTags());

        Map<String, Integer> tableFacets = new HashMap<>();
        Map<String, Integer> schemaFacets = new HashMap<>();
        Map<String, Integer> sourceFacets = new HashMap<>();
        List<QueryHit> matches = new ArrayList<>();
        Pattern queryPattern = compileQueryPattern(criteria.queryRegex());

        sqlRepositoryService.getAllAnalyzedSql(criteria.sourceScope()).forEach(profile -> {
            QueryHit hit = new QueryHit(profile, tagsForQuery(profile, tagsByQueryId));
            if (!matchesQuery(queryPattern, profile) || !matchesNonScope(criteria, hit, digestsWithExamples)) {
                return;
            }
            ScopeKeys keys = scopeKeys(profile);
            if (taggedTables != null && keys.tables().stream().noneMatch(taggedTables::contains)) {
                return;
            }
            if (excludedTaggedTables != null && keys.tables().stream().anyMatch(excludedTaggedTables::contains)) {
                return;
            }
            keys.tables().forEach(key -> tableFacets.merge(key, 1, Integer::sum));
            keys.schemas().forEach(key -> schemaFacets.merge(key, 1, Integer::sum));
            if (matchesScope(criteria, keys)) {
                matches.add(hit);
                if (profile.getSources() != null) {
                    profile.getSources().forEach(source -> sourceFacets.merge(source, 1, Integer::sum));
                }
            }
        });
        return new Sweep(matches, tableFacets, schemaFacets, sourceFacets);
    }

    private record Sweep(List<QueryHit> matches, Map<String, Integer> tableFacets,
                         Map<String, Integer> schemaFacets, Map<String, Integer> sourceFacets) { }

    private List<Tag> tagsForQuery(SqlProfile profile, Map<String, List<Tag>> tags) {
        return profile.getAllIds()
            .stream()
            .map(tags::get)
            .filter(Objects::nonNull)
            .flatMap(List::stream)
            .distinct()
            .toList();
    }

    private Set<String> taggedTables(List<String> names) {
        if (names == null || names.isEmpty()) {
            return null;
        }
        Set<String> wanted = new HashSet<>();
        names.forEach(name -> wanted.add(name.toLowerCase(Locale.ROOT)));
        Set<String> tables = new HashSet<>();
        for (Tag tag : tagsService.findAllByType(TABLE_TAG_TYPE)) {
            String schema = tag.getPathElements().get("schema");
            String table = tag.getPathElements().get("table");
            if (tag.getName() != null && wanted.contains(tag.getName().toLowerCase(Locale.ROOT))
                && schema != null && table != null) {
                tables.add((schema + "." + table).toLowerCase(Locale.ROOT));
            }
        }
        return tables;
    }

    private Map<String, List<Tag>> queryTagsById() {
        return tagsService.findAllByType(QUERY_TAG_TYPE).stream()
            .filter(tag -> tag.getPathElements().get(QUERY_TAG_TYPE) != null)
            .collect(Collectors.groupingBy(tag -> tag.getPathElements().get(QUERY_TAG_TYPE)));
    }

    private boolean matchesNonScope(QuerySearchRequest r, QueryHit hit, Set<String> digestsWithExamples) {
        SqlProfile profile = hit.profile();
        RuntimeMetrics m = profile.getRuntimeMetrics();

        if (!matchesRange(r.avgTimeMs(), m == null ? null : millis(m.getAverageTime()))) return false;
        if (!matchesRange(r.maxTimeMs(), m == null ? null : millis(m.getMaxTime()))) return false;
        if (!matchesRange(r.avgRowsScanned(), m == null ? null : (double) m.getAverageRowsScanned())) return false;
        if (!matchesRange(r.selectivity(), m == null ? null : selectivity(m))) return false;
        if (!matchesRange(r.rowsSorted(), m == null ? null : (double) m.getRowsSorted())) return false;
        if (!matchesRange(r.executed(), m == null ? null : (double) m.getExecuted())) return false;

        if (r.activity() == QuerySearchRequest.Activity.ACTIVE
            && profile.getQueryActivityStatus() != QueryActivityStatus.ACTIVE) return false;
        if (r.activity() == QuerySearchRequest.Activity.DORMANT
            && profile.getQueryActivityStatus() != QueryActivityStatus.DORMANT) return false;

        if (!matchesTypes(r.types(), profile.getType())) return false;
        if (r.excludeTypes() != null && !r.excludeTypes().isEmpty()
            && matchesTypes(r.excludeTypes(), profile.getType())) return false;
        if (isExcludedQuery(r, profile)) return false;

        if (!matchesDates(r.firstSeen(), m == null ? null : m.getFirstSeen())) return false;
        if (!matchesDates(r.lastSeen(), m == null ? null : m.getLastSeen())) return false;

        if (!matchesTags(r, hit.tags())) return false;

        if (!matchesColumns(r, profile)) return false;
        if (!matchesIndexes(r, profile)) return false;
        if (!matchesHinted(r, profile)) return false;
        if (!matchesGenerator(r, profile)) return false;

        if (!matchesFlag(r.fullScan(), m == null ? null : m.getFullTableScan())) return false;
        if (!matchesFlag(r.tmpTable(), m == null ? null : m.getTempTables() > 0)) return false;
        if (!matchesFlag(r.tmpDisk(), m == null ? null : m.getTempDiskTables() > 0)) return false;
        if (!matchesFlag(r.errors(), m == null ? null : m.getErrors() > 0)) return false;
        if (!matchesFlag(r.warnings(), m == null ? null : m.getWarnings() > 0)) return false;

        if (!matchesFlag(r.truncated(), profile.getTruncated())) return false;
        if (!matchesFlag(r.multiSchema(), profile.getIsMultiSchema())) return false;
        if (!matchesRange(r.tableCount(), (double) profile.distinctTableCount())) return false;
        if (!matchesFlag(r.selfJoin(), !profile.selfJoinedTables().isEmpty())) return false;

        boolean hasAnalyzerWarnings = !profile.getMismatchedJoins().isEmpty();
        if (Boolean.TRUE.equals(r.analyzerWarnings()) && !hasAnalyzerWarnings) return false;
        if (Boolean.TRUE.equals(r.hasExamples())
            && !digestsWithExamples.contains(profile.getNormalizedDigest())) return false;
        if (Boolean.TRUE.equals(r.analysisFailed()) && !profile.getAnalysisFailed()) return false;
        if (Boolean.TRUE.equals(r.cleanAnalysis()) && (hasAnalyzerWarnings || profile.getAnalysisFailed())) return false;
        if (Boolean.TRUE.equals(r.analysisSkipped()) && !profile.getAnalysisSkipped()) return false;
        return r.failureKind() == null || matchesFailureKind(r.failureKind(), profile);
    }

    private record ScopeKeys(Set<String> tables, Set<String> schemas) { }

    private static ScopeKeys scopeKeys(SqlProfile profile) {
        Set<String> tables = new HashSet<>();
        Set<String> schemas = new HashSet<>();
        if (profile.getTables() == null) {
            return new ScopeKeys(tables, schemas);
        }
        for (TableReference table : profile.getTables()) {
            String schema = table.getNormalizedSchemaName();
            if (schema == null) {
                continue;
            }
            schemas.add(schema.toLowerCase(Locale.ROOT));
            String name = table.getNormalizedTableName();
            if (name != null) {
                tables.add((schema + "." + name).toLowerCase(Locale.ROOT));
            }
        }
        return new ScopeKeys(tables, schemas);
    }

    private static boolean matchesScope(QuerySearchRequest r, ScopeKeys keys) {
        return matchesTables(r, keys.tables()) && matchesSchemas(r, keys.schemas());
    }

    private static boolean matchesColumns(QuerySearchRequest r, SqlProfile profile) {
        if (r.columns() == null || r.columns().isEmpty()) {
            return true;
        }
        return r.columns().stream().anyMatch(reference -> {
            ColumnReference column;
            try {
                column = ColumnReference.fromString(reference);
            } catch (IllegalArgumentException invalid) {
                return false;
            }
            return profile.constrainedBy(column);
        });
    }

    private static boolean matchesIndexes(QuerySearchRequest r, SqlProfile profile) {
        if (r.indexes() == null || r.indexes().isEmpty()) {
            return true;
        }
        return r.indexes().stream().anyMatch(reference -> {
            ColumnReference index;
            try {
                index = ColumnReference.fromString(reference);
            } catch (IllegalArgumentException invalid) {
                return false;
            }
            return profile.hintsIndex(index.getTable(), index.getNormalizedColumn());
        });
    }

    private static boolean matchesGenerator(QuerySearchRequest r, SqlProfile profile) {
        if (r.generator() == null || r.generator().isBlank()) {
            return true;
        }
        List<com.github.safetytrick.sqlexplorer.avro.GeneratorCandidate> candidates = profile.getGeneratorCandidates();
        if ("none".equals(r.generator())) {
            return candidates == null || candidates.isEmpty();
        }
        return candidates != null && candidates.stream().anyMatch(c -> r.generator().equals(c.getGenerator()));
    }

    private static boolean matchesHinted(QuerySearchRequest r, SqlProfile profile) {
        if (r.hinted() == null) {
            return true;
        }
        List<IndexHint> hints = profile.getIndexHints();
        if (hints == null || hints.isEmpty()) {
            return false;
        }
        return r.hinted() == QuerySearchRequest.HintAction.ANY
            || hints.stream().anyMatch(hint -> r.hinted().name().equalsIgnoreCase(hint.getAction()));
    }

    private static boolean matchesTables(QuerySearchRequest r, Set<String> tableKeys) {
        if (r.excludeTables() != null && !r.excludeTables().isEmpty()) {
            boolean touchesExcluded = r.excludeTables().stream()
                .map(t -> t.toLowerCase(Locale.ROOT))
                .anyMatch(tableKeys::contains);
            if (touchesExcluded) {
                return false;
            }
        }
        if (r.tables() == null || r.tables().isEmpty()) {
            return true;
        }
        List<String> wanted = r.tables().stream().map(t -> t.toLowerCase(Locale.ROOT)).toList();
        return r.tableMode() == QuerySearchRequest.TableMode.ALL
            ? tableKeys.containsAll(wanted)
            : wanted.stream().anyMatch(tableKeys::contains);
    }

    private static boolean matchesSchemas(QuerySearchRequest r, Set<String> schemaKeys) {
        if (r.excludeSchemas() != null && !r.excludeSchemas().isEmpty()) {
            boolean touchesExcluded = r.excludeSchemas().stream()
                .map(s -> s.toLowerCase(Locale.ROOT))
                .anyMatch(schemaKeys::contains);
            if (touchesExcluded) {
                return false;
            }
        }
        if (r.schemas() == null) {
            return true;
        }
        if (r.schemas().isEmpty()) {
            return false;
        }
        return r.schemas().stream().map(s -> s.toLowerCase(Locale.ROOT)).anyMatch(schemaKeys::contains);
    }

    private static final String ANALYZER_FAILURE_PREFIX = "Failure on:";

    private static boolean matchesFailureKind(QuerySearchRequest.FailureKind kind, SqlProfile profile) {
        if (!profile.getAnalysisFailed() || profile.getTruncated()) {
            return false;
        }
        String reason = profile.getAnalysisFailureReason();
        if (reason == null) {
            return false;
        }
        boolean analyzerFailure = reason.startsWith(ANALYZER_FAILURE_PREFIX);
        return (kind == QuerySearchRequest.FailureKind.ANALYZER) == analyzerFailure;
    }

    private static Pattern compileQueryPattern(String regex) {
        if (regex == null || regex.isBlank()) {
            return null;
        }
        try {
            return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException invalid) {
            return Pattern.compile(Pattern.quote(regex), Pattern.CASE_INSENSITIVE);
        }
    }

    private static boolean matchesQuery(Pattern pattern, SqlProfile profile) {
        if (pattern == null) {
            return true;
        }
        String query = profile.getQuery();
        return query != null && pattern.matcher(query).find();
    }

    static boolean matchesTypes(List<String> wanted, String profileType) {
        if (wanted == null || wanted.isEmpty()) {
            return true;
        }
        String type = switch (profileType == null ? "" : profileType.toUpperCase(Locale.ROOT)) {
            case "(" -> "SELECT";
            case "WITH" -> StatementTypes.UNKNOWN;
            case String other -> other;
        };
        for (String candidate : wanted) {
            if (candidate == null) {
                continue;
            }
            String upper = candidate.toUpperCase(Locale.ROOT);
            boolean matches = switch (upper) {
                case OTHER_TYPE -> !EXPLICIT_TYPES.contains(type);
                case "WITH" -> type.equals("SELECT") || type.equals(StatementTypes.UNKNOWN);
                default -> upper.equals(type);
            };
            if (matches) {
                return true;
            }
        }
        return false;
    }

    private static boolean isExcludedQuery(QuerySearchRequest r, SqlProfile profile) {
        if (r.excludeQueries() == null || r.excludeQueries().isEmpty()) {
            return false;
        }
        Set<String> excluded = Set.copyOf(r.excludeQueries());
        return excluded.contains(profile.getDigest())
            || profile.getAllIds().stream().anyMatch(excluded::contains);
    }

    private boolean matchesTags(QuerySearchRequest r, List<Tag> tags) {
        if (r.excludeTags() != null && !r.excludeTags().isEmpty()
            && tags.stream().map(Tag::getName).anyMatch(r.excludeTags()::contains)) {
            return false;
        }
        if (Boolean.TRUE.equals(r.untaggedOnly())) {
            return tags.isEmpty();
        }
        if (r.tags() == null || r.tags().isEmpty()) {
            return true;
        }
        List<String> names = tags.stream().map(Tag::getName).toList();
        return r.tagMode() == QuerySearchRequest.TagMode.ALL
            ? names.containsAll(r.tags())
            : r.tags().stream().anyMatch(names::contains);
    }

    private static boolean matchesRange(Range range, Double value) {
        if (range == null || (range.min() == null && range.max() == null)) return true;
        if (value == null) return false;
        if (range.min() != null && value < range.min()) return false;
        return range.max() == null || value <= range.max();
    }

    private static boolean matchesDates(DateRange range, LocalDateTime value) {
        if (range == null || (range.after() == null && range.before() == null)) return true;
        if (value == null) return false;
        if (range.after() != null && value.isBefore(range.after())) return false;
        return range.before() == null || !value.isAfter(range.before());
    }

    private static boolean matchesFlag(Flag flag, Boolean value) {
        if (flag == null || flag == Flag.ANY) return true;
        if (value == null) return false;
        return (flag == Flag.YES) == value;
    }

    private static Double millis(BigDecimal picos) {
        return picos == null ? null : picos.doubleValue() / PICOS_PER_MILLI;
    }

    private static Double selectivity(RuntimeMetrics m) {
        return m.getAverageRowsScanned() == 0
            ? 1.0
            : m.getAverageRowsSent() / (double) m.getAverageRowsScanned();
    }

    private static Comparator<QueryHit> comparator(QuerySearchRequest.Sort sort) {
        QuerySearchRequest.Sort key = sort == null ? QuerySearchRequest.Sort.IMPACT : sort;
        Function<RuntimeMetrics, Double> metric = switch (key) {
            case IMPACT -> m -> millis(m.getTotalTime());
            case AVG_TIME -> m -> millis(m.getAverageTime());
            case MAX_TIME -> m -> millis(m.getMaxTime());
            case AVG_ROWS_SCANNED -> m -> (double) m.getAverageRowsScanned();
            case SELECTIVITY, SELECTIVITY_ASC -> QuerySearchService::selectivity;
            case EXECUTIONS -> m -> (double) m.getExecuted();
            case LAST_SEEN -> m -> m.getLastSeen() == null ? null : (double) m.getLastSeen().toEpochSecond(java.time.ZoneOffset.UTC);
        };
        Function<QueryHit, Double> extract = hit -> hit.profile().getRuntimeMetrics() == null
            ? null
            : metric.apply(hit.profile().getRuntimeMetrics());
        Comparator<Double> order = key == QuerySearchRequest.Sort.SELECTIVITY_ASC
            ? Comparator.naturalOrder()
            : Comparator.reverseOrder();
        return Comparator.comparing(extract, Comparator.nullsLast(order));
    }
}
