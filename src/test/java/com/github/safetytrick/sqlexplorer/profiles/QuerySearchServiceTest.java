package com.github.safetytrick.sqlexplorer.profiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.avro.JoinColumns;
import com.github.safetytrick.sqlexplorer.avro.QueryActivityStatus;
import com.github.safetytrick.sqlexplorer.avro.RuntimeMetrics;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.commentary.Tag;
import com.github.safetytrick.sqlexplorer.commentary.TagsService;
import com.github.safetytrick.sqlexplorer.evidence.SqlRepositoryService;
import com.github.safetytrick.sqlexplorer.profiles.QuerySearchRequest.Activity;
import com.github.safetytrick.sqlexplorer.profiles.QuerySearchRequest.DateRange;
import com.github.safetytrick.sqlexplorer.profiles.QuerySearchRequest.Flag;
import com.github.safetytrick.sqlexplorer.profiles.QuerySearchRequest.Range;
import com.github.safetytrick.sqlexplorer.profiles.QuerySearchRequest.Sort;
import com.github.safetytrick.sqlexplorer.profiles.QuerySearchRequest.TagMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QuerySearchServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 12, 12, 0);

    private SqlRepositoryService sqlRepositoryService;
    private TagsService tagsService;
    private QuerySearchService service;

    @BeforeEach
    void setUp() {
        sqlRepositoryService = mock(SqlRepositoryService.class);
        tagsService = mock(TagsService.class);
        service = new QuerySearchService(sqlRepositoryService, tagsService);
        when(tagsService.findAllByType("query")).thenReturn(List.of());
    }

    private void repositoryReturns(SqlProfile... profiles) {
        when(sqlRepositoryService.getAllAnalyzedSql()).thenAnswer(inv -> Stream.of(profiles));
    }

    private List<String> searchIds(QuerySearchRequest request) {
        return service.search(request).results().stream().map(hit -> hit.profile().getId()).toList();
    }

    @Test
    void unconstrainedSearchReturnsEverythingWorstAvgTimeFirst() {
        repositoryReturns(
            profile("fast", metrics().avgTimeMs(2).build()),
            profile("slow", metrics().avgTimeMs(50).build()),
            profile("unmetered", null));

        QuerySearchResult result = service.search(null);

        assertThat(result.total()).isEqualTo(3);
        assertThat(result.results()).extracting(hit -> hit.profile().getId())
            .containsExactly("slow", "fast", "unmetered");
    }

    @Test
    void timeRangeIsExpressedInMilliseconds() {
        repositoryReturns(
            profile("fast", metrics().avgTimeMs(2).build()),
            profile("slow", metrics().avgTimeMs(50).build()),
            profile("unmetered", null));

        List<String> ids = searchIds(request().avgTimeMs(new Range(10d, null)).build());

        assertThat(ids).containsExactly("slow");
    }

    @Test
    void triStateFlagsMatchYesAndNo() {
        repositoryReturns(
            profile("scanner", metrics().fullScan(true).build()),
            profile("indexed", metrics().fullScan(false).build()),
            profile("unmetered", null));

        assertThat(searchIds(request().fullScan(Flag.YES).build())).containsExactly("scanner");
        assertThat(searchIds(request().fullScan(Flag.NO).build())).containsExactly("indexed");
        assertThat(searchIds(request().fullScan(Flag.ANY).build()))
            .containsExactlyInAnyOrder("scanner", "indexed", "unmetered");
    }

    @Test
    void selectivityTreatsZeroScannedAsPerfectlySelective() {
        repositoryReturns(
            profile("insert", metrics().rowsSentAvg(0).rowsScannedAvg(0).build()),
            profile("broad", metrics().rowsSentAvg(1).rowsScannedAvg(1000).build()));

        assertThat(searchIds(request().selectivity(new Range(0.5, null)).build())).containsExactly("insert");
        assertThat(searchIds(request().selectivity(new Range(null, 0.01)).build())).containsExactly("broad");
    }

    @Test
    void activityAndLastSeenFilter() {
        repositoryReturns(
            profile("recent", QueryActivityStatus.ACTIVE, metrics().lastSeen(NOW.minusDays(1)).build(), false, List.of()),
            profile("stale", QueryActivityStatus.DORMANT, metrics().lastSeen(NOW.minusDays(90)).build(), false, List.of()));

        assertThat(searchIds(request().activity(Activity.DORMANT).build())).containsExactly("stale");
        assertThat(searchIds(request()
            .lastSeen(new DateRange(NOW.minusDays(10).toLocalDate(), null)).build()))
            .containsExactly("recent");
    }

    @Test
    void tagFiltersMatchAnyAllAndUntagged() {
        when(tagsService.findAllByType("query")).thenReturn(List.of(
            tag("audit", "/query/a"), tag("slow", "/query/a"), tag("audit", "/query/b")));
        repositoryReturns(
            profile("a", metrics().build()),
            profile("b", metrics().build()),
            profile("c", metrics().build()));

        assertThat(searchIds(request().tags(List.of("audit", "slow")).tagMode(TagMode.ANY).build()))
            .containsExactlyInAnyOrder("a", "b");
        assertThat(searchIds(request().tags(List.of("audit", "slow")).tagMode(TagMode.ALL).build()))
            .containsExactly("a");
        assertThat(searchIds(request().untaggedOnly(true).build())).containsExactly("c");
    }

    @Test
    void analyzerFiltersUseMismatchedJoinsAndFailureFlag() {
        repositoryReturns(
            profile("warned", QueryActivityStatus.UNKNOWN, metrics().build(), false, List.of(new JoinColumns(null, null))),
            profile("failed", QueryActivityStatus.UNKNOWN, metrics().build(), true, List.of()),
            profile("clean", QueryActivityStatus.UNKNOWN, metrics().build(), false, List.of()));

        assertThat(searchIds(request().analyzerWarnings(true).build())).containsExactly("warned");
        assertThat(searchIds(request().analysisFailed(true).build())).containsExactly("failed");
        assertThat(searchIds(request().cleanAnalysis(true).build())).containsExactly("clean");
    }

    @Test
    void pagingReportsTotalBeforeSlicing() {
        repositoryReturns(
            profile("q1", metrics().avgTimeMs(30).build()),
            profile("q2", metrics().avgTimeMs(20).build()),
            profile("q3", metrics().avgTimeMs(10).build()));

        QuerySearchResult page = service.search(request().limit(1).offset(1).build());

        assertThat(page.total()).isEqualTo(3);
        assertThat(page.results()).extracting(hit -> hit.profile().getId()).containsExactly("q2");
    }

    @Test
    void sortByLastSeenPutsNewestFirst() {
        repositoryReturns(
            profile("old", metrics().lastSeen(NOW.minusDays(30)).build()),
            profile("new", metrics().lastSeen(NOW).build()),
            profile("unmetered", null));

        assertThat(searchIds(request().sort(Sort.LAST_SEEN).build()))
            .containsExactly("new", "old", "unmetered");
    }

    // ── fixtures ──

    private static SqlProfile profile(String id, RuntimeMetrics metrics) {
        return profile(id, QueryActivityStatus.UNKNOWN, metrics, false, List.of());
    }

    private static SqlProfile profile(String id, QueryActivityStatus activity, RuntimeMetrics metrics,
                                      boolean analysisFailed, List<JoinColumns> mismatchedJoins) {
        SqlProfile profile = new SqlProfile();
        profile.setId(id);
        profile.setQuery("select 1");
        profile.setType("SELECT");
        profile.setDigest(id + "-digest");
        profile.setNormalizedDigest(id + "-norm");
        profile.setSchemaName("test");
        profile.setQueryActivityStatus(activity);
        profile.setRuntimeMetrics(metrics);
        profile.setAnalysisFailed(analysisFailed);
        profile.setMismatchedJoins(mismatchedJoins);
        return profile;
    }

    private static Tag tag(String name, String path) {
        Tag tag = new Tag();
        tag.setName(name);
        tag.setPath(path);
        tag.setType("query");
        return tag;
    }

    private static MetricsBuilder metrics() {
        return new MetricsBuilder();
    }

    /** RuntimeMetrics with harmless defaults; times accepted in ms, stored as picoseconds. */
    private static class MetricsBuilder {
        private double avgTimeMs = 1;
        private double maxTimeMs = 1;
        private boolean fullScan = false;
        private long rowsSentAvg = 1;
        private long rowsScannedAvg = 1;
        private LocalDateTime lastSeen = NOW;

        MetricsBuilder avgTimeMs(double v) { this.avgTimeMs = v; return this; }
        MetricsBuilder fullScan(boolean v) { this.fullScan = v; return this; }
        MetricsBuilder rowsSentAvg(long v) { this.rowsSentAvg = v; return this; }
        MetricsBuilder rowsScannedAvg(long v) { this.rowsScannedAvg = v; return this; }
        MetricsBuilder lastSeen(LocalDateTime v) { this.lastSeen = v; return this; }

        RuntimeMetrics build() {
            return new RuntimeMetrics(fullScan, 100L, 0L, 0L,
                picos(avgTimeMs * 100), picos(maxTimeMs), picos(avgTimeMs),
                rowsSentAvg * 100, rowsSentAvg, rowsScannedAvg * 100, rowsScannedAvg,
                0L, 0L, 0L, 0L,
                "digest", lastSeen, lastSeen.minusDays(100),
                picos(0.1), 0L, 0L, List.of());
        }

        private static BigDecimal picos(double ms) {
            return BigDecimal.valueOf(ms * 1_000_000_000L);
        }
    }

    /** Builder over the request record so tests only mention what they constrain. */
    private static RequestBuilder request() {
        return new RequestBuilder();
    }

    private static class RequestBuilder {
        private Range avgTimeMs, selectivity;
        private Activity activity;
        private DateRange lastSeen;
        private List<String> tags;
        private TagMode tagMode;
        private Boolean untaggedOnly, analyzerWarnings, analysisFailed, cleanAnalysis;
        private Flag fullScan;
        private Sort sort;
        private Integer limit, offset;

        RequestBuilder avgTimeMs(Range v) { this.avgTimeMs = v; return this; }
        RequestBuilder selectivity(Range v) { this.selectivity = v; return this; }
        RequestBuilder activity(Activity v) { this.activity = v; return this; }
        RequestBuilder lastSeen(DateRange v) { this.lastSeen = v; return this; }
        RequestBuilder tags(List<String> v) { this.tags = v; return this; }
        RequestBuilder tagMode(TagMode v) { this.tagMode = v; return this; }
        RequestBuilder untaggedOnly(Boolean v) { this.untaggedOnly = v; return this; }
        RequestBuilder analyzerWarnings(Boolean v) { this.analyzerWarnings = v; return this; }
        RequestBuilder analysisFailed(Boolean v) { this.analysisFailed = v; return this; }
        RequestBuilder cleanAnalysis(Boolean v) { this.cleanAnalysis = v; return this; }
        RequestBuilder fullScan(Flag v) { this.fullScan = v; return this; }
        RequestBuilder sort(Sort v) { this.sort = v; return this; }
        RequestBuilder limit(Integer v) { this.limit = v; return this; }
        RequestBuilder offset(Integer v) { this.offset = v; return this; }

        QuerySearchRequest build() {
            return new QuerySearchRequest(avgTimeMs, null, null, selectivity, null,
                activity, null, lastSeen, tags, tagMode, untaggedOnly,
                fullScan, null, null, null, null,
                analyzerWarnings, analysisFailed, cleanAnalysis, sort, limit, offset);
        }
    }
}
