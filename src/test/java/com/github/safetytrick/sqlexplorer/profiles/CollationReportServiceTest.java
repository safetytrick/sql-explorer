package com.github.safetytrick.sqlexplorer.profiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.sql.JDBCType;
import java.util.List;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.avro.ColumnReference;
import com.github.safetytrick.sqlexplorer.avro.JoinColumns;
import com.github.safetytrick.sqlexplorer.avro.QueryActivityStatus;
import com.github.safetytrick.sqlexplorer.avro.RuntimeMetrics;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.evidence.SqlRepositoryService;
import com.github.safetytrick.sqlexplorer.profiles.CollationReportService.CollationReport;
import com.github.safetytrick.sqlexplorer.profiles.CollationReportService.ColumnEncoding;
import com.github.safetytrick.sqlexplorer.profiles.CollationReportService.JoinEncodingMismatch;
import com.github.safetytrick.sqlexplorer.profiles.CollationReportService.TableEncoding;
import com.github.safetytrick.sqlexplorer.profiles.CollationReportService.TextColumn;
import com.github.safetytrick.sqlexplorer.schema.SqlSchemaService;
import com.github.safetytrick.sqlexplorer.schema.TableColumn;
import com.github.safetytrick.sqlexplorer.schema.TableStats;
import com.github.safetytrick.sqlexplorer.schema.TableStructure;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CollationReportServiceTest {

    private static final String UNICODE = "utf8mb4_0900_ai_ci";
    private static final String BIN = "utf8mb4_bin";
    private static final String LEGACY = "utf8mb3_general_ci";

    private static final TableStructure ACCOUNT = table("account",
        column("account", "id", null, null),
        column("account", "code", "utf8mb4", UNICODE),
        column("account", "name", "utf8mb4", UNICODE),
        column("account", "token", "utf8mb4", BIN));
    private static final TableStructure SITE = table("site",
        column("site", "id", null, null),
        column("site", "account_code", "utf8mb3", LEGACY),
        column("site", "label", "utf8mb3", LEGACY));
    private static final TableStructure ACCOUNT_SITE = table("account_site",
        column("account_site", "account_id", null, null),
        column("account_site", "site_id", null, null));

    private SqlRepositoryService sqlRepositoryService;
    private CollationReportService service;

    @BeforeEach
    void setUp() {
        SqlSchemaService sqlSchemaService = mock(SqlSchemaService.class);
        sqlRepositoryService = mock(SqlRepositoryService.class);
        service = new CollationReportService(sqlSchemaService, sqlRepositoryService);
        when(sqlSchemaService.getTables()).thenReturn(List.of(SITE, ACCOUNT, ACCOUNT_SITE));
        when(sqlSchemaService.getTable(any())).thenAnswer(inv -> {
            TableReference reference = inv.getArgument(0);
            return Stream.of(ACCOUNT, SITE, ACCOUNT_SITE)
                .filter(table -> table.name().equals(reference.getNormalizedTableName()))
                .findFirst().orElse(null);
        });
    }

    @Test
    void groupsColumnsByEncodingAndFlagsTablesThatMix() {
        when(sqlRepositoryService.getAllAnalyzedSql()).thenAnswer(inv -> Stream.empty());

        CollationReport report = service.getReport();

        assertThat(report.tables())
            .extracting(TableEncoding::table, TableEncoding::textColumns, TableEncoding::predominantCollation,
                TableEncoding::mixedCharsets, TableEncoding::mixedCollations)
            .containsExactly(
                tuple("account", 3, UNICODE, false, true),
                tuple("account_site", 0, null, false, false),
                tuple("site", 2, LEGACY, false, false));
        TableEncoding account = report.tables().getFirst();
        assertThat(account.encodings()).hasSize(2);
        assertThat(account.encodings().getFirst().columns()).containsExactly("code", "name");
        assertThat(account.outliers()).extracting(ColumnEncoding::column).containsExactly("token");
        assertThat(report.tables().get(2).outliers()).isEmpty();
    }

    @Test
    void comparesColumnsWithTheDeclaredDefaultWhenItIsKnown() {
        TableStructure site = withDefault(SITE, "latin1_swedish_ci", "latin1_swedish_ci");

        TableEncoding encoding = CollationReportService.encodingOf(site);

        assertThat(encoding.baseline()).isEqualTo("declared");
        assertThat(encoding.declaredCollation()).isEqualTo("latin1_swedish_ci");
        assertThat(encoding.declaredCharset()).isEqualTo("latin1");
        assertThat(encoding.mixedCollations()).isFalse();
        assertThat(encoding.outliers()).extracting(ColumnEncoding::column).containsExactly("account_code", "label");
    }

    @Test
    void listsEveryCharacterColumnWithWhatItPresumablyHolds() {
        TableEncoding encoding = CollationReportService.encodingOf(
            withDefault(SITE, "latin1_swedish_ci", "latin1_swedish_ci"));

        assertThat(encoding.columns()).extracting(TextColumn::column, TextColumn::type, TextColumn::kind,
                TextColumn::differs)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("account_code", "VARCHAR(20)", "identifier", true),
                org.assertj.core.groups.Tuple.tuple("label", "VARCHAR(20)", "text", true));
    }

    @Test
    void fallsBackToThePredominantEncodingWhenShardsDisagreeOrNoneReported() {
        TableEncoding disagree = CollationReportService.encodingOf(
            withDefault(ACCOUNT, "latin1_swedish_ci", UNICODE));
        TableEncoding unreported = CollationReportService.encodingOf(ACCOUNT);

        for (TableEncoding encoding : List.of(disagree, unreported)) {
            assertThat(encoding.baseline()).isEqualTo("predominant");
            assertThat(encoding.declaredCollation()).isNull();
            assertThat(encoding.outliers()).extracting(ColumnEncoding::column).containsExactly("token");
        }
    }

    @Test
    void reportsJoinKeysWhoseSidesDifferOncePerPair() {
        when(sqlRepositoryService.getAllAnalyzedSql()).thenAnswer(inv -> Stream.of(
            profile("q1", 100, join("account", "code", "site", "account_code")),
            profile("q2", 900, join("site", "account_code", "account", "code"),
                join("site", "account_code", "account", "code")),
            profile("q3", 5, join("account_site", "account_id", "account", "id"),
                join("account", "code", "account", "name"))));

        List<JoinEncodingMismatch> joins = service.getReport().joins();

        assertThat(joins).hasSize(1);
        JoinEncodingMismatch mismatch = joins.getFirst();
        assertThat(mismatch.left().table()).isEqualTo("account");
        assertThat(mismatch.right().collation()).isEqualTo(LEGACY);
        assertThat(mismatch.charsetDiffers()).isTrue();
        assertThat(mismatch.queries()).isEqualTo(2);
        assertThat(mismatch.executions()).isEqualTo(1_000);
        assertThat(mismatch.exampleDigest()).isEqualTo("q2");
    }

    private static TableStructure table(String name, TableColumn... columns) {
        return new TableStructure("app_main", name, "TABLE", null, List.of(), List.of(), List.of(columns), null, null);
    }

    private static TableStructure withDefault(TableStructure table, String... collations) {
        List<TableStats> stats = Stream.of(collations)
            .map(collation -> new TableStats("shard", Instant.EPOCH, 10L, 16_384L, 0L, 0L, 1_638L, null,
                "Dynamic", "InnoDB", null, null, collation))
            .toList();
        return table.withStats(stats);
    }

    private static TableColumn column(String table, String name, String charset, String collation) {
        return new TableColumn("app_main", table, name, charset == null ? JDBCType.BIGINT : JDBCType.VARCHAR,
            charset == null ? "BIGINT" : "VARCHAR", 20, 0, 10, null, null, 80, 1, "NO", "NO", "NO",
            charset, collation);
    }

    private static JoinColumns join(String leftTable, String leftColumn, String rightTable, String rightColumn) {
        return new JoinColumns(
            new ColumnReference(TableReference.newTableReference(null, leftTable), leftColumn, leftColumn),
            new ColumnReference(TableReference.newTableReference(null, rightTable), rightColumn, rightColumn),
            "INNER");
    }

    private static SqlProfile profile(String digest, long executed, JoinColumns... joins) {
        SqlProfile profile = new SqlProfile();
        profile.setId(digest);
        profile.setQuery("select 1");
        profile.setType("SELECT");
        profile.setDigest(digest);
        profile.setNormalizedDigest(digest);
        profile.setSchemaName("app_main");
        profile.setQueryActivityStatus(QueryActivityStatus.UNKNOWN);
        profile.setRuntimeMetrics(new RuntimeMetrics(false, executed, 0L, 0L,
            BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
            digest, null, null, BigDecimal.ZERO, 0L, 0L, List.of()));
        profile.setJoinColumns(List.of(joins));
        return profile;
    }
}
