package com.github.safetytrick.sqlexplorer.profiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.safetytrick.sqlexplorer.avro.IndexHint;
import com.github.safetytrick.sqlexplorer.avro.JoinColumns;
import com.github.safetytrick.sqlexplorer.avro.QueryActivityStatus;
import com.github.safetytrick.sqlexplorer.avro.RuntimeMetrics;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.commentary.Tag;
import com.github.safetytrick.sqlexplorer.commentary.TagsService;
import com.github.safetytrick.sqlexplorer.examples.QueryExampleLookup;
import com.github.safetytrick.sqlexplorer.evidence.SqlRepositoryService;
import extensions.com.github.safetytrick.sqlexplorer.avro.ColumnReference.ColumnReferenceExtension;
import com.github.safetytrick.sqlexplorer.profiles.QuerySearchRequest.Activity;
import com.github.safetytrick.sqlexplorer.profiles.QuerySearchRequest.DateRange;
import com.github.safetytrick.sqlexplorer.profiles.QuerySearchRequest.Flag;
import com.github.safetytrick.sqlexplorer.profiles.QuerySearchRequest.HintAction;
import com.github.safetytrick.sqlexplorer.profiles.QuerySearchRequest.Range;
import com.github.safetytrick.sqlexplorer.profiles.QuerySearchRequest.Sort;
import com.github.safetytrick.sqlexplorer.profiles.QuerySearchRequest.TableMode;
import com.github.safetytrick.sqlexplorer.profiles.QuerySearchRequest.TagMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class QuerySearchServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 12, 12, 0);

    private SqlRepositoryService sqlRepositoryService;
    private TagsService tagsService;
    private QueryExampleLookup queryExampleLookup;
    private QuerySearchService service;

    @BeforeEach
    void setUp() {
        sqlRepositoryService = mock(SqlRepositoryService.class);
        tagsService = mock(TagsService.class);
        queryExampleLookup = mock(QueryExampleLookup.class);
        when(queryExampleLookup.digestsWithExamples()).thenReturn(Set.of());
        service = new QuerySearchService(sqlRepositoryService, tagsService, queryExampleLookup);
        when(tagsService.findAllByType("query")).thenReturn(List.of());
    }

    private void repositoryReturns(SqlProfile... profiles) {
        when(sqlRepositoryService.getAllAnalyzedSql()).thenAnswer(inv -> Stream.of(profiles));
    }

    private List<String> searchIds(QuerySearchRequest request) {
        return service.search(request).results().stream().map(hit -> hit.profile().getId()).toList();
    }

    @Test
    void unconstrainedSearchReturnsEverythingHighestImpactFirst() {
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
            .lastSeen(new DateRange(NOW.minusDays(10), null)).build()))
            .containsExactly("recent");
    }

    @Test
    void lastSeenFiltersAtHourGranularity() {
        repositoryReturns(
            profile("night-shift", metrics().lastSeen(NOW.minusHours(20)).build()),
            profile("this-morning", metrics().lastSeen(NOW.minusHours(2)).build()));

        assertThat(searchIds(request()
            .lastSeen(new DateRange(NOW.minusHours(16), null)).build()))
            .containsExactly("this-morning");
        assertThat(searchIds(request()
            .lastSeen(new DateRange(null, NOW.minusHours(16))).build()))
            .containsExactly("night-shift");
    }

    @Test
    void dateRangeJsonAcceptsDatesAndDateTimes() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        // date-only strings keep their historic day-inclusive semantics
        QuerySearchRequest dateOnly = mapper.readValue(
            "{\"firstSeen\":{\"after\":\"2026-07-01\",\"before\":\"2026-07-30\"}}",
            QuerySearchRequest.class);
        assertThat(dateOnly.firstSeen().after()).isEqualTo(LocalDateTime.of(2026, 7, 1, 0, 0));
        assertThat(dateOnly.firstSeen().before()).isEqualTo(LocalDateTime.of(2026, 7, 30, 23, 59, 59, 999_999_999));

        // full date-times, with 'T' or space separator
        QuerySearchRequest dateTime = mapper.readValue(
            "{\"lastSeen\":{\"after\":\"2026-07-01T14:30\",\"before\":\"2026-07-02 06:15:30\"}}",
            QuerySearchRequest.class);
        assertThat(dateTime.lastSeen().after()).isEqualTo(LocalDateTime.of(2026, 7, 1, 14, 30));
        assertThat(dateTime.lastSeen().before()).isEqualTo(LocalDateTime.of(2026, 7, 2, 6, 15, 30));

        // nulls pass through
        QuerySearchRequest open = mapper.readValue(
            "{\"lastSeen\":{\"after\":null,\"before\":\"\"}}", QuerySearchRequest.class);
        assertThat(open.lastSeen().after()).isNull();
        assertThat(open.lastSeen().before()).isNull();
    }

    @Test
    void typeFilterMatchesMultipleTypesAndOther() {
        repositoryReturns(
            typed("select-q", "SELECT"),
            typed("insert-q", "INSERT"),
            typed("grant-q", "GRANT"),
            typed("flush-q", "flush"),
            typed("untyped-q", null));

        assertThat(searchIds(request().types(List.of("SELECT", "INSERT")).build()))
            .containsExactlyInAnyOrder("select-q", "insert-q");
        // OTHER catches everything outside the explicit set, including missing types
        assertThat(searchIds(request().types(List.of("OTHER")).build()))
            .containsExactlyInAnyOrder("grant-q", "flush-q", "untyped-q");
        assertThat(searchIds(request().types(List.of("select", "other")).build()))
            .containsExactlyInAnyOrder("select-q", "grant-q", "flush-q", "untyped-q");
        assertThat(searchIds(request().types(List.of()).build()))
            .hasSize(5);
    }

    private static SqlProfile typed(String id, String type) {
        SqlProfile profile = profile(id, metrics().build());
        profile.setType(type);
        return profile;
    }

    @Test
    void truncatedAndMultiSchemaTriStates() {
        SqlProfile truncated = profile("truncated", metrics().build());
        truncated.setTruncated(true);
        SqlProfile multi = profile("multi", metrics().build());
        multi.setIsMultiSchema(true);
        repositoryReturns(truncated, multi, profile("plain", metrics().build()));

        assertThat(searchIds(request().truncated(Flag.YES).build())).containsExactly("truncated");
        assertThat(searchIds(request().truncated(Flag.NO).build()))
            .containsExactlyInAnyOrder("multi", "plain");
        assertThat(searchIds(request().multiSchema(Flag.YES).build())).containsExactly("multi");
    }

    @Test
    void analysisSkippedAndFailureKindsMirrorTheReports() {
        SqlProfile skipped = profile("skipped", metrics().build());
        skipped.setAnalysisSkipped(true);
        SqlProfile analyzerFailure = profile("analyzer-failure", metrics().build());
        analyzerFailure.setAnalysisFailed(true);
        analyzerFailure.setAnalysisFailureReason("Failure on: JoinColumnAnalyzer message: boom");
        SqlProfile parseFailure = profile("parse-failure", metrics().build());
        parseFailure.setAnalysisFailed(true);
        parseFailure.setAnalysisFailureReason("Encountered unexpected token");
        // truncated failures are excluded from both kinds, like the reports
        SqlProfile truncatedFailure = profile("truncated-failure", metrics().build());
        truncatedFailure.setAnalysisFailed(true);
        truncatedFailure.setTruncated(true);
        truncatedFailure.setAnalysisFailureReason("Failure on: SchemaAnalyzer");
        repositoryReturns(skipped, analyzerFailure, parseFailure, truncatedFailure, profile("clean", metrics().build()));

        assertThat(searchIds(request().analysisSkipped(true).build())).containsExactly("skipped");
        assertThat(searchIds(request().failureKind(QuerySearchRequest.FailureKind.ANALYZER).build()))
            .containsExactly("analyzer-failure");
        assertThat(searchIds(request().failureKind(QuerySearchRequest.FailureKind.PARSE).build()))
            .containsExactly("parse-failure");
    }

    @Test
    void tagFiltersMatchAnyAllAndUntagged() {
        when(tagsService.findAllByType("query")).thenReturn(List.of(
            tag("audit", "a-norm"), tag("slow", "a-digest"), tag("audit", "b-norm")));
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

    /**
     * "Which queries can I actually plan?" An example is what makes a query
     * runnable — the corpus text carries `?` placeholders, which EXPLAIN
     * rejects — so this filter is how you find the ones worth opening.
     */
    @Test
    void hasExamplesKeepsOnlyQueriesWithAStoredExample() {
        repositoryReturns(
            profile("has", QueryActivityStatus.UNKNOWN, metrics().build(), false, List.of()),
            profile("none", QueryActivityStatus.UNKNOWN, metrics().build(), false, List.of()));
        when(queryExampleLookup.digestsWithExamples()).thenReturn(Set.of("has-norm"));

        assertThat(searchIds(request().hasExamples(true).build())).containsExactly("has");
    }

    /** Off by default, and off means unfiltered rather than empty. */
    @Test
    void hasExamplesIsNotAppliedUnlessAskedFor() {
        repositoryReturns(
            profile("has", QueryActivityStatus.UNKNOWN, metrics().build(), false, List.of()),
            profile("none", QueryActivityStatus.UNKNOWN, metrics().build(), false, List.of()));
        when(queryExampleLookup.digestsWithExamples()).thenReturn(Set.of("has-norm"));

        assertThat(searchIds(request().build())).containsExactlyInAnyOrder("has", "none");
        // and the lookup is not even consulted when the filter is off
        verify(queryExampleLookup, never()).digestsWithExamples();
    }

    @Test
    void analyzerFiltersUseMismatchedJoinsAndFailureFlag() {
        repositoryReturns(
            profile("warned", QueryActivityStatus.UNKNOWN, metrics().build(), false, List.of(new JoinColumns(null, null, null))),
            profile("failed", QueryActivityStatus.UNKNOWN, metrics().build(), true, List.of()),
            profile("clean", QueryActivityStatus.UNKNOWN, metrics().build(), false, List.of()));

        assertThat(searchIds(request().analyzerWarnings(true).build())).containsExactly("warned");
        assertThat(searchIds(request().analysisFailed(true).build())).containsExactly("failed");
        assertThat(searchIds(request().cleanAnalysis(true).build())).containsExactly("clean");
    }

    @Test
    void tableScopeMatchesAnyAndAll() {
        repositoryReturns(
            withTables("orders", "app.orders"),
            withTables("orders-users", "app.orders", "app.users"),
            withTables("billing", "billing.invoices"),
            profile("no-tables", metrics().build()));

        assertThat(searchIds(request().tables(List.of("app.orders")).build()))
            .containsExactlyInAnyOrder("orders", "orders-users");
        assertThat(searchIds(request().tables(List.of("app.orders", "app.users")).tableMode(TableMode.ALL).build()))
            .containsExactly("orders-users");
        // selection casing need not match the casing captured in analysis
        assertThat(searchIds(request().tables(List.of("APP.ORDERS")).build()))
            .containsExactlyInAnyOrder("orders", "orders-users");
    }

    @Test
    void schemaScopeMatchesAnyListedSchema() {
        repositoryReturns(
            withTables("orders", "app.orders"),
            withTables("billing", "billing.invoices"));

        assertThat(searchIds(request().schemas(List.of("billing")).build())).containsExactly("billing");
        assertThat(searchIds(request().schemas(List.of("app", "billing")).build()))
            .containsExactlyInAnyOrder("orders", "billing");
    }

    @Test
    void excludeSchemasDropsQueriesThatTouchAnyExcludedSchema() {
        repositoryReturns(
            withTables("orders", "app.orders"),
            withTables("billing", "billing.invoices"),
            withTables("cross", "app.orders", "billing.invoices"));

        assertThat(searchIds(request().excludeSchemas(List.of("billing")).build()))
            .containsExactly("orders");
        assertThat(searchIds(request().schemas(List.of("app")).excludeSchemas(List.of("billing")).build()))
            .containsExactly("orders");
        assertThat(searchIds(request().schemas(List.of()).build())).isEmpty();
    }

    @Test
    void excludeTablesDropsQueriesThatReferenceAnyExcludedTable() {
        repositoryReturns(
            withTables("orders", "app.orders"),
            withTables("audit", "app.audit_log"),
            withTables("cross", "app.orders", "app.audit_log"));

        // on its own, with no table selection at all — the case that catches
        // the exclusion being checked after the "nothing selected" short circuit
        assertThat(searchIds(request().excludeTables(List.of("app.audit_log")).build()))
            .containsExactly("orders");
        // case-insensitive, like every other scope key
        assertThat(searchIds(request().excludeTables(List.of("APP.AUDIT_LOG")).build()))
            .containsExactly("orders");
        // exclusion beats an include naming the same table
        assertThat(searchIds(request()
            .tables(List.of("app.audit_log")).excludeTables(List.of("app.audit_log")).build()))
            .isEmpty();
    }

    @Test
    void schemaAndTableExclusionsBothApply() {
        repositoryReturns(
            withTables("orders", "app.orders"),
            withTables("audit", "app.audit_log"),
            withTables("billing", "billing.invoices"));

        assertThat(searchIds(request().excludeSchemas(List.of("billing")).build()))
            .containsExactlyInAnyOrder("orders", "audit");
        assertThat(searchIds(request().excludeTables(List.of("app.audit_log")).build()))
            .containsExactlyInAnyOrder("orders", "billing");
        // together they are additive — each drops its own, neither cancels the other
        assertThat(searchIds(request()
            .excludeSchemas(List.of("billing")).excludeTables(List.of("app.audit_log")).build()))
            .containsExactly("orders");
    }

    @Test
    void facetsCountEachTableOncePerProfileOverTheNonScopeSet() {
        repositoryReturns(
            withTables("orders", "app.orders", "app.orders"), // self-join lists a table twice
            withTables("orders-users", "app.orders", "app.users"),
            withTables("billing", "billing.invoices"));

        // scoping to one table narrows the results, but facets still reflect the full non-scope set
        QuerySearchResult result = service.search(request().tables(List.of("billing.invoices")).build());

        assertThat(result.results()).extracting(hit -> hit.profile().getId()).containsExactly("billing");
        assertThat(result.facets().tables())
            .containsEntry("app.orders", 2)
            .containsEntry("app.users", 1)
            .containsEntry("billing.invoices", 1);
        assertThat(result.facets().schemas())
            .containsEntry("app", 2)
            .containsEntry("billing", 1);
    }

    private static SqlProfile withTables(String id, String... schemaTableKeys) {
        SqlProfile profile = profile(id, metrics().build());
        profile.setTables(Stream.of(schemaTableKeys).map(key -> {
            String[] parts = key.split("\\.", 2);
            return new TableReference(parts[0], parts[1], parts[1], parts[0]);
        }).toList());
        return profile;
    }


    /** A profile whose column usage is set explicitly: criteria, join, select-only. */
    private static SqlProfile withColumns(String id, String table,
                                          List<String> criteria, List<String> joins, List<String> selects) {
        SqlProfile profile = withTables(id, table);
        profile.setCriteriaColumns(criteria.stream()
            .map(ColumnReferenceExtension::fromString).toList());
        profile.setSelectColumns(selects.stream()
            .map(ColumnReferenceExtension::fromString).toList());
        profile.setJoinColumns(joins.stream().map(pair -> {
            String[] sides = pair.split(" = ");
            return JoinColumns.newBuilder()
                .setLeft(ColumnReferenceExtension.fromString(sides[0]))
                .setRight(ColumnReferenceExtension.fromString(sides[1]))
                .build();
        }).toList());
        return profile;
    }

    @Test
    void columnFilterKeepsQueriesTheColumnConstrains() {
        repositoryReturns(
            withColumns("criteria-hit", "app.orders", List.of("app.orders.status"), List.of(), List.of()),
            withColumns("join-hit", "app.orders", List.of(), List.of("app.orders.id = app.lines.order_id"), List.of()),
            withColumns("elsewhere", "app.orders", List.of("app.orders.created_at"), List.of(), List.of()));

        assertThat(searchIds(request().columns(List.of("app.orders.status")).build()))
            .containsExactly("criteria-hit");
        assertThat(searchIds(request().columns(List.of("app.lines.order_id")).build()))
            .containsExactly("join-hit");
        // several columns are an ANY match — the drill-down widens, never narrows
        assertThat(searchIds(request().columns(List.of("app.orders.status", "app.orders.created_at")).build()))
            .containsExactlyInAnyOrder("criteria-hit", "elsewhere");
    }

    /**
     * The case that separates "same behaviour as the table view" from a naive
     * match against any column reference: {@code constrainedBy} counts joins and
     * criteria, never the select list.
     */
    @Test
    void aColumnThatIsOnlySelectedIsNotAUsage() {
        repositoryReturns(
            withColumns("selects-it", "app.orders", List.of(), List.of(), List.of("app.orders.note")),
            withColumns("filters-it", "app.orders", List.of("app.orders.note"), List.of(), List.of()));

        assertThat(searchIds(request().columns(List.of("app.orders.note")).build()))
            .containsExactly("filters-it");
    }

    @Test
    void columnFilterComposesWithTheTableScopeAndSurvivesBadInput() {
        repositoryReturns(
            withColumns("in-scope", "app.orders", List.of("app.orders.status"), List.of(), List.of()),
            withColumns("other-table", "app.archive", List.of("app.orders.status"), List.of(), List.of()));

        assertThat(searchIds(request()
            .tables(List.of("app.orders")).columns(List.of("app.orders.status")).build()))
            .containsExactly("in-scope");
        // a malformed reference out of a URL matches nothing rather than throwing
        assertThat(searchIds(request().columns(List.of("nonsense")).build())).isEmpty();
    }

    @Test
    void indexFilterKeepsQueriesWhoseHintsNameTheIndex() {
        SqlProfile forced = withColumns("forced", "app.orders", List.of(), List.of(), List.of());
        forced.setIndexHints(List.of(new IndexHint(TableReference.fromString("app.orders"),
            "`Orders_Status_Idx`", "orders_status_idx", "FORCE")));
        SqlProfile ignored = withColumns("ignored", "app.orders", List.of(), List.of(), List.of());
        ignored.setIndexHints(List.of(new IndexHint(TableReference.fromString("app.orders"),
            "PRIMARY", "primary", "IGNORE")));
        repositoryReturns(forced, ignored, withColumns("unhinted", "app.orders", List.of(), List.of(), List.of()));

        assertThat(searchIds(request().indexes(List.of("app.orders.orders_status_idx")).build()))
            .containsExactly("forced");
        // any action counts, and the name compares as MySQL compares it
        assertThat(searchIds(request().indexes(List.of("app.orders.PRIMARY", "app.orders.orders_status_idx")).build()))
            .containsExactlyInAnyOrder("forced", "ignored");
        assertThat(searchIds(request().indexes(List.of("app.archive.primary")).build())).isEmpty();
        assertThat(searchIds(request().indexes(List.of("nonsense")).build())).isEmpty();
        assertThat(searchIds(request().indexes(List.of()).build())).containsExactlyInAnyOrder("forced", "ignored", "unhinted");
    }

    @Test
    void hintedFilterKeepsQueriesCarryingAnyHintOrOneAction() {
        SqlProfile forced = withColumns("forced", "app.orders", List.of(), List.of(), List.of());
        forced.setIndexHints(List.of(new IndexHint(TableReference.fromString("app.orders"),
            "orders_status_idx", "orders_status_idx", "FORCE")));
        SqlProfile used = withColumns("used", "app.account", List.of(), List.of(), List.of());
        used.setIndexHints(List.of(new IndexHint(TableReference.fromString("app.account"),
            "account_site_idx", "account_site_idx", "USE")));
        repositoryReturns(forced, used, withColumns("unhinted", "app.orders", List.of(), List.of(), List.of()));

        assertThat(searchIds(request().hinted(HintAction.ANY).build())).containsExactlyInAnyOrder("forced", "used");
        assertThat(searchIds(request().hinted(HintAction.FORCE).build())).containsExactly("forced");
        assertThat(searchIds(request().hinted(HintAction.IGNORE).build())).isEmpty();
        assertThat(searchIds(request().hinted(null).build())).containsExactlyInAnyOrder("forced", "used", "unhinted");
        // composes with the table scope
        assertThat(searchIds(request().tables(List.of("app.account")).hinted(HintAction.ANY).build()))
            .containsExactly("used");
    }

    @Test
    void noColumnFilterLeavesTheSearchUnconstrained() {
        repositoryReturns(
            withColumns("a", "app.orders", List.of("app.orders.status"), List.of(), List.of()),
            withColumns("b", "app.orders", List.of(), List.of(), List.of("app.orders.note")));

        assertThat(searchIds(request().build())).containsExactlyInAnyOrder("a", "b");
        assertThat(searchIds(request().columns(List.of()).build())).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void queryRegexMatchesSqlTextCaseInsensitivelyWithLiteralFallback() {
        repositoryReturns(
            queried("orders", "SELECT * FROM orders WHERE status = ?"),
            queried("users", "SELECT id FROM users"),
            queried("insert", "INSERT INTO orders (id) VALUES (?)"));

        // case-insensitive substring
        assertThat(searchIds(request().queryRegex("from orders").build())).containsExactly("orders");
        // regex anchors / alternation
        assertThat(searchIds(request().queryRegex("^SELECT").build()))
            .containsExactlyInAnyOrder("orders", "users");
        assertThat(searchIds(request().queryRegex("orders|users").build()))
            .containsExactlyInAnyOrder("orders", "users", "insert");
        // blank is not a constraint
        assertThat(searchIds(request().queryRegex("   ").build())).hasSize(3);
        // an invalid regex falls back to a literal search rather than erroring
        assertThat(searchIds(request().queryRegex("VALUES (").build())).containsExactly("insert");
        assertThat(searchIds(request().queryRegex("(status").build())).isEmpty();
    }

    private static SqlProfile queried(String id, String sql) {
        SqlProfile profile = profile(id, metrics().build());
        profile.setQuery(sql);
        return profile;
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
    void defaultSortIsImpactNotAvgTime() {
        repositoryReturns(
            // slow but rare: 200ms × 10 = 2,000ms impact
            profile("slow-rare", metrics().avgTimeMs(200).executed(10).build()),
            // fast but constant: 2ms × 100,000 = 200,000ms impact
            profile("fast-hot", metrics().avgTimeMs(2).executed(100_000).build()),
            profile("unmetered", null));

        assertThat(searchIds(request().build()))
            .containsExactly("fast-hot", "slow-rare", "unmetered");
        // explicit avg-time sort still puts the slow query first
        assertThat(searchIds(request().sort(Sort.AVG_TIME).build()))
            .containsExactly("slow-rare", "fast-hot", "unmetered");
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

    /**
     * A query tag, addressed by digest rather than by a path string — the tag
     * model is typed now, so the fixture is built the way production stores it
     * (sqlx-11v).
     */
    private static Tag tag(String name, String digest) {
        Tag tag = new Tag();
        tag.setName(name);
        tag.setType("query");
        tag.setQueryDigest(digest);
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
        private long executed = 100;
        private long rowsSentAvg = 1;
        private long rowsScannedAvg = 1;
        private LocalDateTime lastSeen = NOW;

        MetricsBuilder avgTimeMs(double v) { this.avgTimeMs = v; return this; }
        MetricsBuilder fullScan(boolean v) { this.fullScan = v; return this; }
        MetricsBuilder executed(long v) { this.executed = v; return this; }
        MetricsBuilder rowsSentAvg(long v) { this.rowsSentAvg = v; return this; }
        MetricsBuilder rowsScannedAvg(long v) { this.rowsScannedAvg = v; return this; }
        MetricsBuilder lastSeen(LocalDateTime v) { this.lastSeen = v; return this; }

        RuntimeMetrics build() {
            return new RuntimeMetrics(fullScan, executed, 0L, 0L,
                picos(avgTimeMs * executed), picos(maxTimeMs), picos(avgTimeMs),
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
        private String queryRegex;
        private Range avgTimeMs, selectivity;
        private Activity activity;
        private List<String> types;
        private DateRange lastSeen;
        private List<String> tags;
        private TagMode tagMode;
        private List<String> schemas, excludeSchemas, tables, excludeTables, columns, indexes;
        private TableMode tableMode;
        private HintAction hinted;
        private Boolean untaggedOnly, analyzerWarnings, hasExamples, analysisFailed, cleanAnalysis, analysisSkipped;
        private QuerySearchRequest.FailureKind failureKind;
        private Flag fullScan, truncated, multiSchema;
        private Sort sort;
        private Integer limit, offset;

        RequestBuilder queryRegex(String v) { this.queryRegex = v; return this; }
        RequestBuilder avgTimeMs(Range v) { this.avgTimeMs = v; return this; }
        RequestBuilder selectivity(Range v) { this.selectivity = v; return this; }
        RequestBuilder activity(Activity v) { this.activity = v; return this; }
        RequestBuilder types(List<String> v) { this.types = v; return this; }
        RequestBuilder lastSeen(DateRange v) { this.lastSeen = v; return this; }
        RequestBuilder tags(List<String> v) { this.tags = v; return this; }
        RequestBuilder tagMode(TagMode v) { this.tagMode = v; return this; }
        RequestBuilder schemas(List<String> v) { this.schemas = v; return this; }
        RequestBuilder excludeSchemas(List<String> v) { this.excludeSchemas = v; return this; }
        RequestBuilder excludeTables(List<String> v) { this.excludeTables = v; return this; }
        RequestBuilder columns(List<String> v) { this.columns = v; return this; }
        RequestBuilder indexes(List<String> v) { this.indexes = v; return this; }
        RequestBuilder hinted(HintAction v) { this.hinted = v; return this; }
        RequestBuilder tables(List<String> v) { this.tables = v; return this; }
        RequestBuilder tableMode(TableMode v) { this.tableMode = v; return this; }
        RequestBuilder untaggedOnly(Boolean v) { this.untaggedOnly = v; return this; }
        RequestBuilder analyzerWarnings(Boolean v) { this.analyzerWarnings = v; return this; }
        RequestBuilder analysisFailed(Boolean v) { this.analysisFailed = v; return this; }
        RequestBuilder cleanAnalysis(Boolean v) { this.cleanAnalysis = v; return this; }
        RequestBuilder analysisSkipped(Boolean v) { this.analysisSkipped = v; return this; }
        RequestBuilder failureKind(QuerySearchRequest.FailureKind v) { this.failureKind = v; return this; }
        RequestBuilder fullScan(Flag v) { this.fullScan = v; return this; }
        RequestBuilder truncated(Flag v) { this.truncated = v; return this; }
        RequestBuilder multiSchema(Flag v) { this.multiSchema = v; return this; }
        RequestBuilder hasExamples(Boolean v) { this.hasExamples = v; return this; }
        RequestBuilder sort(Sort v) { this.sort = v; return this; }
        RequestBuilder limit(Integer v) { this.limit = v; return this; }
        RequestBuilder offset(Integer v) { this.offset = v; return this; }

        QuerySearchRequest build() {
            return new QuerySearchRequest(queryRegex, avgTimeMs, null, null, selectivity, null,
                activity, types, null, lastSeen, tags, tagMode, untaggedOnly,
                schemas, excludeSchemas, tables, tableMode, excludeTables, columns, indexes, hinted,
                fullScan, null, null, null, null,
                truncated, multiSchema,
                analyzerWarnings, hasExamples, analysisFailed, cleanAnalysis, analysisSkipped, failureKind, sort, limit, offset);
        }
    }
}
