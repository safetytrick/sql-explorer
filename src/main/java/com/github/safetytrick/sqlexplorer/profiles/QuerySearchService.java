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

/**
 * Filters, sorts and pages the active environment's analyzed queries. All
 * predicates evaluate fields already on {@link SqlProfile}/{@link RuntimeMetrics};
 * a query without runtime metrics is excluded whenever a metric-based
 * constraint is set.
 */
@Service
public class QuerySearchService {

    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 1000;

    /** Types offered as explicit filters; "OTHER" matches everything outside this set. */
    public static final Set<String> EXPLICIT_TYPES = Set.of(
        "SELECT", "INSERT", "UPDATE", "DELETE", "CREATE", "ALTER", "DROP", "SHOW", "WITH");
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
            new QuerySearchResult.Facets(sweep.tableFacets(), sweep.schemaFacets()));
    }

    /**
     * Every profile matching the request's filters and scope — unsorted,
     * unpaged (limit/offset ignored). Feeds set-oriented consumers like the
     * graph and bulk tagging; {@link #search} remains the paged/faceted entry
     * point.
     *
     * <p>Both go through {@link #sweep}, and that matters beyond tidiness.
     * These were two hand-copied loops over the same predicates, which is fine
     * until the two counts reach a user together: bulk tagging shows "tag 1,847
     * queries?" from this method while the workbench header shows its total
     * from {@code search}. Two loops that must stay in sync eventually do not,
     * and the disagreement would surface as a confirmation the page contradicts
     * (sqlx-agl).
     */
    public List<SqlProfile> matchingProfiles(QuerySearchRequest request) {
        return sweep(request == null ? QuerySearchRequest.UNCONSTRAINED : request).matches().stream()
            .map(QueryHit::profile)
            .toList();
    }

    /**
     * One pass over the corpus: what matched, and the facet counts.
     *
     * <p>Facets count everything passing the non-scope filters, while the
     * matches are those that also pass the table/schema scope — which is why
     * this is one traversal rather than two calls.
     */
    private Sweep sweep(QuerySearchRequest criteria) {
        // Fetched once per search rather than per profile: this filters the
        // whole corpus, and a lookup per row would be thousands of round trips
        // to answer one checkbox. Empty unless the filter is actually on.
        Set<String> digestsWithExamples = Boolean.TRUE.equals(criteria.hasExamples())
            ? queryExampleLookup.digestsWithExamples()
            : Set.of();
        Map<String, List<Tag>> tagsByQueryId = queryTagsById();
        Set<String> taggedTables = taggedTables(criteria.tableTags());

        Map<String, Integer> tableFacets = new HashMap<>();
        Map<String, Integer> schemaFacets = new HashMap<>();
        List<QueryHit> matches = new ArrayList<>();
        // compiled once, not per profile
        Pattern queryPattern = compileQueryPattern(criteria.queryRegex());

        sqlRepositoryService.getAllAnalyzedSql().forEach(profile -> {
            QueryHit hit = new QueryHit(profile, tagsForQuery(profile, tagsByQueryId));
            if (!matchesQuery(queryPattern, profile) || !matchesNonScope(criteria, hit, digestsWithExamples)) {
                return;
            }
            ScopeKeys keys = scopeKeys(profile);
            if (taggedTables != null && keys.tables().stream().noneMatch(taggedTables::contains)) {
                return;
            }
            keys.tables().forEach(key -> tableFacets.merge(key, 1, Integer::sum));
            keys.schemas().forEach(key -> schemaFacets.merge(key, 1, Integer::sum));
            if (matchesScope(criteria, keys)) {
                matches.add(hit);
            }
        });
        return new Sweep(matches, tableFacets, schemaFacets);
    }

    private record Sweep(List<QueryHit> matches, Map<String, Integer> tableFacets,
                         Map<String, Integer> schemaFacets) { }

    private List<Tag> tagsForQuery(SqlProfile profile, Map<String, List<Tag>> tags) {
        return profile.getAllIds()
            .stream()
            .map(tags::get)
            .filter(Objects::nonNull)
            .flatMap(List::stream)
            .distinct()
            .toList();
    }

    /**
     * The {@code schema.table} keys carrying any of these table tags, or null
     * when the filter is off. Read once per search, like the query tags.
     */
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

        if (r.activity() == QuerySearchRequest.Activity.ACTIVE
            && profile.getQueryActivityStatus() != QueryActivityStatus.ACTIVE) return false;
        if (r.activity() == QuerySearchRequest.Activity.DORMANT
            && profile.getQueryActivityStatus() != QueryActivityStatus.DORMANT) return false;

        if (!matchesTypes(r.types(), profile.getType())) return false;

        if (!matchesDates(r.firstSeen(), m == null ? null : m.getFirstSeen())) return false;
        if (!matchesDates(r.lastSeen(), m == null ? null : m.getLastSeen())) return false;

        if (!matchesTags(r, hit.tags())) return false;

        if (!matchesColumns(r, profile)) return false;
        if (!matchesIndexes(r, profile)) return false;
        if (!matchesHinted(r, profile)) return false;

        if (!matchesFlag(r.fullScan(), m == null ? null : m.getFullTableScan())) return false;
        if (!matchesFlag(r.tmpTable(), m == null ? null : m.getTempTables() > 0)) return false;
        if (!matchesFlag(r.tmpDisk(), m == null ? null : m.getTempDiskTables() > 0)) return false;
        if (!matchesFlag(r.errors(), m == null ? null : m.getErrors() > 0)) return false;
        if (!matchesFlag(r.warnings(), m == null ? null : m.getWarnings() > 0)) return false;

        if (!matchesFlag(r.truncated(), profile.getTruncated())) return false;
        if (!matchesFlag(r.multiSchema(), profile.getIsMultiSchema())) return false;

        boolean hasAnalyzerWarnings = !profile.getMismatchedJoins().isEmpty();
        if (Boolean.TRUE.equals(r.analyzerWarnings()) && !hasAnalyzerWarnings) return false;
        if (Boolean.TRUE.equals(r.hasExamples())
            && !digestsWithExamples.contains(profile.getNormalizedDigest())) return false;
        if (Boolean.TRUE.equals(r.analysisFailed()) && !profile.getAnalysisFailed()) return false;
        if (Boolean.TRUE.equals(r.cleanAnalysis()) && (hasAnalyzerWarnings || profile.getAnalysisFailed())) return false;
        if (Boolean.TRUE.equals(r.analysisSkipped()) && !profile.getAnalysisSkipped()) return false;
        return r.failureKind() == null || matchesFailureKind(r.failureKind(), profile);
    }

    /** A profile's normalized {@code schema.table} keys and schema keys, keyed to match the schema tree. */
    private record ScopeKeys(Set<String> tables, Set<String> schemas) { }

    /**
     * Builds the scope keys the way {@link SchemaTreeService} keys its nodes
     * (normalized schema/table), lower-cased so selection and facet lookups
     * line up regardless of the casing captured in analysis.
     */
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

    /**
     * The column drill-down: keep queries that a listed column *constrains* —
     * it appears in a join or in the criteria. Delegates to
     * {@code SqlProfile.constrainedBy} rather than comparing serialized
     * references, so normalization (case, backticks, default schema) behaves
     * exactly as it does everywhere else and a select-only reference does not
     * count as usage.
     *
     * <p>Unparseable references match nothing rather than throwing: these
     * arrive from a URL, and a malformed one is a bad link, not a server error.
     */
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

    /**
     * Index references from {@link QuerySearchRequest#indexes()}: a query is
     * kept when its hints name any of them. Shaped and parsed as column
     * references are — the last part names the index — and, as there, an
     * unparseable one matches nothing.
     */
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

    /** {@link QuerySearchRequest#hinted()}: any index hint, or one with the given action, on any table. */
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

    /** ANY = references any selected table; ALL = references all of them. Empty = unconstrained. */
    private static boolean matchesTables(QuerySearchRequest r, Set<String> tableKeys) {
        // exclusion is checked before the "no tables selected" short circuit,
        // exactly as matchesSchemas does: excluding a table has to work on
        // its own, and the common case is no table selection at all
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

    /** Include: a query in any listed schema matches. {@code null} = unconstrained;
     *  empty list = match nothing. Exclude: a query that touches any listed schema is dropped. */
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

    /** Matches SqlAnalysisService's failure labeling: analyzer failures carry a "Failure on:" reason. */
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

    /**
     * Compiles the SQL search into a case-insensitive pattern. Blank = no filter
     * (null). An invalid regex falls back to a literal (quoted) search so a
     * half-typed pattern still does something sensible instead of erroring.
     */
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

    /** "OTHER" matches any type outside {@link #EXPLICIT_TYPES} (including missing types). */
    private static boolean matchesTypes(List<String> wanted, String profileType) {
        if (wanted == null || wanted.isEmpty()) {
            return true;
        }
        String type = profileType == null ? "" : profileType.toUpperCase(Locale.ROOT);
        for (String candidate : wanted) {
            if (candidate == null) {
                continue;
            }
            String upper = candidate.toUpperCase(Locale.ROOT);
            if (OTHER_TYPE.equals(upper) ? !EXPLICIT_TYPES.contains(type) : upper.equals(type)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesTags(QuerySearchRequest r, List<Tag> tags) {
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

    /** Rows sent / rows scanned; a query that scans nothing is perfectly selective. */
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
            case SELECTIVITY -> QuerySearchService::selectivity;
            case LAST_SEEN -> m -> m.getLastSeen() == null ? null : (double) m.getLastSeen().toEpochSecond(java.time.ZoneOffset.UTC);
        };
        Function<QueryHit, Double> extract = hit -> hit.profile().getRuntimeMetrics() == null
            ? null
            : metric.apply(hit.profile().getRuntimeMetrics());
        return Comparator.comparing(extract, Comparator.nullsLast(Comparator.reverseOrder()));
    }
}
