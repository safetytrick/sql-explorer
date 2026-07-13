package com.github.safetytrick.sqlexplorer.profiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.github.safetytrick.sqlexplorer.avro.QueryActivityStatus;
import com.github.safetytrick.sqlexplorer.avro.RuntimeMetrics;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.commentary.Tag;
import com.github.safetytrick.sqlexplorer.commentary.TagsService;
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
        "SELECT", "INSERT", "UPDATE", "DELETE", "CREATE", "ALTER", "DROP", "SHOW");
    public static final String OTHER_TYPE = "OTHER";

    private static final String QUERY_TAG_TYPE = "query";
    private static final double PICOS_PER_MILLI = 1_000_000_000d;

    private final SqlRepositoryService sqlRepositoryService;
    private final TagsService tagsService;

    public QuerySearchService(SqlRepositoryService sqlRepositoryService, TagsService tagsService) {
        this.sqlRepositoryService = sqlRepositoryService;
        this.tagsService = tagsService;
    }

    public QuerySearchResult search(QuerySearchRequest request) {
        QuerySearchRequest criteria = request == null ? QuerySearchRequest.UNCONSTRAINED : request;
        Map<String, List<Tag>> tagsByQueryId = queryTagsById();

        List<QueryHit> matches = sqlRepositoryService.getAllAnalyzedSql()
            .map(profile -> new QueryHit(profile, tagsByQueryId.getOrDefault(profile.getId(), List.of())))
            .filter(hit -> matches(criteria, hit))
            .sorted(comparator(criteria.sort()))
            .toList();

        int offset = Math.max(criteria.offset() == null ? 0 : criteria.offset(), 0);
        int limit = Math.min(criteria.limit() == null || criteria.limit() < 1 ? DEFAULT_LIMIT : criteria.limit(), MAX_LIMIT);
        int from = Math.min(offset, matches.size());
        int to = Math.min(from + limit, matches.size());
        return new QuerySearchResult(matches.size(), matches.subList(from, to));
    }

    private Map<String, List<Tag>> queryTagsById() {
        return tagsService.findAllByType(QUERY_TAG_TYPE).stream()
            .filter(tag -> tag.getPathElements().get(QUERY_TAG_TYPE) != null)
            .collect(Collectors.groupingBy(tag -> tag.getPathElements().get(QUERY_TAG_TYPE)));
    }

    private boolean matches(QuerySearchRequest r, QueryHit hit) {
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

        if (!matchesFlag(r.fullScan(), m == null ? null : m.getFullTableScan())) return false;
        if (!matchesFlag(r.tmpTable(), m == null ? null : m.getTempTables() > 0)) return false;
        if (!matchesFlag(r.tmpDisk(), m == null ? null : m.getTempDiskTables() > 0)) return false;
        if (!matchesFlag(r.errors(), m == null ? null : m.getErrors() > 0)) return false;
        if (!matchesFlag(r.warnings(), m == null ? null : m.getWarnings() > 0)) return false;

        boolean hasAnalyzerWarnings = !profile.getMismatchedJoins().isEmpty();
        if (Boolean.TRUE.equals(r.analyzerWarnings()) && !hasAnalyzerWarnings) return false;
        if (Boolean.TRUE.equals(r.analysisFailed()) && !profile.getAnalysisFailed()) return false;
        if (Boolean.TRUE.equals(r.cleanAnalysis()) && (hasAnalyzerWarnings || profile.getAnalysisFailed())) return false;

        return true;
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
        if (range.after() != null && value.toLocalDate().isBefore(range.after())) return false;
        return range.before() == null || !value.toLocalDate().isAfter(range.before());
    }

    private static boolean matchesFlag(Flag flag, Boolean value) {
        if (flag == null || flag == Flag.ANY) return true;
        if (value == null) return false;
        return flag == Flag.YES ? value : !value;
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
            case IMPACT -> m -> {
                Double avgMs = millis(m.getAverageTime());
                return avgMs == null ? null : avgMs * m.getExecuted();
            };
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
