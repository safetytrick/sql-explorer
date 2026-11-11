package com.github.safetytrick.sqlexplorer.ddl;

import java.sql.JDBCType;
import java.time.Instant;
import java.util.List;

import com.github.safetytrick.sqlexplorer.avro.ColumnReference;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.ddl.DdlPreflightService.Algorithm;
import com.github.safetytrick.sqlexplorer.ddl.DdlPreflightService.AlgorithmChoice;
import com.github.safetytrick.sqlexplorer.ddl.DdlPreflightService.Outcome;
import com.github.safetytrick.sqlexplorer.ddl.DdlPreflightService.Check;
import com.github.safetytrick.sqlexplorer.ddl.DdlPreflightService.Preflight;
import com.github.safetytrick.sqlexplorer.ddl.DdlPreflightService.Severity;
import com.github.safetytrick.sqlexplorer.profiles.TableWorkloadService;
import com.github.safetytrick.sqlexplorer.profiles.TableWorkloadService.LockRisk;
import com.github.safetytrick.sqlexplorer.profiles.TableWorkloadService.WriteRate;
import com.github.safetytrick.sqlexplorer.schema.ForeignKey;
import com.github.safetytrick.sqlexplorer.schema.IndexDirection;
import com.github.safetytrick.sqlexplorer.schema.IndexType;
import com.github.safetytrick.sqlexplorer.schema.SqlSchemaService;
import com.github.safetytrick.sqlexplorer.schema.TableColumn;
import com.github.safetytrick.sqlexplorer.schema.TableIndex;
import com.github.safetytrick.sqlexplorer.schema.TableStats;
import com.github.safetytrick.sqlexplorer.schema.TableStructure;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DdlPreflightServiceTest {

    private DdlPreflightService service;

    /** account: a latin1 table with a unique code, 2 M rows on 3 GB, referenced by account_site. */
    private static final TableStructure ACCOUNT = new TableStructure("app_main", "account", "TABLE", null,
        List.of(),
        List.of(index("PRIMARY", true, "id"), index("uq_code", true, "code"), index("idx_name", false, "name")),
        List.of(column("account", "id", "BIGINT", 19, null, null),
            column("account", "code", "VARCHAR", 40, "latin1", "latin1_swedish_ci"),
            column("account", "name", "VARCHAR", 255, "latin1", "latin1_swedish_ci"),
            column("account", "notes", "TEXT", 65_535, "latin1", "latin1_swedish_ci")),
        null, null,
        List.of(new TableStats("shard1", Instant.EPOCH, 2_000_000L, 2_500_000_000L, 500_000_000L, 0L, 1_250L, null,
            "Dynamic", "InnoDB", null, null, "latin1_swedish_ci")));
    private static final TableStructure ACCOUNT_SITE = new TableStructure("app_main", "account_site", "TABLE", null,
        List.of(new ForeignKey.Builder().addFromAndTo(
            new ColumnReference(TableReference.newTableReference("app_main", "account_site"), "account_id", "account_id"),
            new ColumnReference(TableReference.newTableReference("app_main", "account"), "id", "id")).build()),
        List.of(), List.of(column("account_site", "account_id", "BIGINT", 19, null, null)), null, null);

    @BeforeEach
    void setUp() {
        SqlSchemaService schema = mock(SqlSchemaService.class);
        TableWorkloadService workload = mock(TableWorkloadService.class);
        when(schema.getTable(any())).thenAnswer(inv -> {
            TableReference ref = inv.getArgument(0);
            return "account".equals(ref.getNormalizedTableName()) ? ACCOUNT : null;
        });
        when(schema.getTables()).thenReturn(List.of(ACCOUNT, ACCOUNT_SITE));
        when(workload.getWriteRate(any())).thenReturn(new WriteRate(9_000, 9_000, 300, 300, null, null, List.of(),
            List.of()));
        when(workload.getLockRisk(any(), anyDouble())).thenReturn(new LockRisk(12, 1_000, 2, 40, 95_000, 300, false,
            List.of()));
        service = new DdlPreflightService(schema, workload);
    }

    @Test
    void anInstantAddColumnRunsNativelyWithALockTimeout() {
        Preflight preflight = service.check("ALTER TABLE app_main.account ADD COLUMN name_u8 VARCHAR(255) "
            + "CHARACTER SET utf8mb4 NULL, ALGORITHM=INSTANT", 1_000);

        assertThat(preflight.algorithm()).isEqualTo(Algorithm.INSTANT);
        assertThat(preflight.concurrentDml()).isTrue();
        assertThat(preflight.tableFacts().rowsEstimate()).isEqualTo(2_000_000L);
        assertThat(preflight.tableFacts().referencedBy()).containsExactly("app_main.account_site");
        assertThat(preflight.checks()).extracting(Check::title).contains("Uses an instant row version",
            "Row size within the limit");
        assertThat(String.join(" ", preflight.recommendations())).contains("lock_wait_timeout = 2")
            .contains("2 captured statements have run longer than 1 s (slowest 2 min)");
    }

    @Test
    void aCharsetChangeCopiesAndWarnsAboutDoubleEncodingAndUniqueIndexes() {
        Preflight preflight = service.check("ALTER TABLE account MODIFY code VARCHAR(40) CHARACTER SET utf8mb4 "
            + "COLLATE utf8mb4_0900_ai_ci", 1_000);

        assertThat(preflight.algorithm()).isEqualTo(Algorithm.COPY);
        assertThat(preflight.concurrentDml()).isFalse();
        assertThat(preflight.checks()).extracting(Check::title).contains(
            "latin1 → UTF-8 conversion transcodes the bytes", "Unique index uq_code under a new collation",
            "Foreign keys");
        assertThat(String.join(" ", preflight.recommendations())).contains("pt-online-schema-change");
    }

    @Test
    void aRequestedAlgorithmThatCannotBeMetIsCalledOut() {
        Preflight preflight = service.check("ALTER TABLE account MODIFY name VARCHAR(255) CHARACTER SET utf8mb4, "
            + "ALGORITHM=INSTANT", 1_000);

        assertThat(preflight.checks()).anyMatch(c -> c.severity() == Severity.BLOCK
            && c.title().equals("MySQL will refuse this as written"));
    }

    @Test
    void widenAVarcharWithinItsLengthPrefixIsMetadataOnlyButAcrossItCopies() {
        assertThat(service.check("ALTER TABLE account MODIFY code VARCHAR(200)", 1_000).algorithm())
            .isEqualTo(Algorithm.INPLACE);
        assertThat(service.check("ALTER TABLE account MODIFY code VARCHAR(300)", 1_000).algorithm())
            .isEqualTo(Algorithm.COPY);
    }

    @Test
    void anIndexOverTheKeyLimitIsBlockedAndMixedClausesAreFlagged() {
        Preflight preflight = service.check("ALTER TABLE account ADD COLUMN body VARCHAR(1000) CHARACTER SET utf8mb4,"
            + " ADD INDEX idx_body (body)", 1_000);

        assertThat(preflight.algorithm()).isEqualTo(Algorithm.INPLACE);
        assertThat(preflight.checks()).extracting(Check::title).contains("Index idx_body over the key length limit",
            "Clauses with different costs in one statement");
    }

    @Test
    void anUnknownTableOrStatementSaysSo() {
        assertThat(service.check("ALTER TABLE nowhere ADD COLUMN x INT", 1_000).checks())
            .extracting(Check::title).contains("Table not in the schema snapshot");
        assertThat(service.check("SELECT 1", 1_000).error()).isNotNull();
    }

    @Test
    void aMissingCommaBlocksAndTheRestIsReadAsCorrected() {
        Preflight preflight = service.check(
            "ALTER TABLE app_main.account CONVERT TO CHARACTER SET utf8mb4 ALGORITHM=COPY", 1_000);

        assertThat(preflight.algorithm()).isEqualTo(Algorithm.COPY);
        assertThat(preflight.requestedAlgorithm()).isEqualTo("COPY");
        assertThat(preflight.checks()).filteredOn(c -> c.severity() == Severity.BLOCK)
            .extracting(Check::title).contains("MySQL rejects this syntax");
    }

    @Test
    void describesEachAlgorithmThatCouldBeStated() {
        Preflight preflight = service.check("ALTER TABLE app_main.account ADD COLUMN x INT NULL, ALGORITHM=INPLACE",
            1_000);

        assertThat(preflight.choices()).extracting(AlgorithmChoice::algorithm, AlgorithmChoice::outcome,
            AlgorithmChoice::requested).containsExactly(
                org.assertj.core.groups.Tuple.tuple("DEFAULT", Outcome.CHOSEN, false),
                org.assertj.core.groups.Tuple.tuple("INSTANT", Outcome.NEEDED, false),
                org.assertj.core.groups.Tuple.tuple("INPLACE", Outcome.SLOWER, true),
                org.assertj.core.groups.Tuple.tuple("COPY", Outcome.SLOWER, false));
        assertThat(preflight.choices().get(2).implication()).contains("rebuilds the table");

        Preflight convert = service.check("ALTER TABLE app_main.account CONVERT TO CHARACTER SET utf8mb4", 1_000);
        assertThat(convert.choices()).extracting(AlgorithmChoice::outcome)
            .containsExactly(Outcome.CHOSEN, Outcome.REFUSED, Outcome.REFUSED, Outcome.NEEDED);
        assertThat(convert.choices().get(3).implication()).contains("blocks writes").contains("GB");
    }

    private static TableIndex index(String name, boolean unique, String... columns) {
        TableIndex.Builder builder = TableIndex.builder().schema("app_main").name(name).unique(unique)
            .indexType(IndexType.tableIndexOther).direction(IndexDirection.ASCENDING);
        for (int i = 0; i < columns.length; i++) {
            builder.addColumn((short) (i + 1), columns[i]);
        }
        return builder.build();
    }

    private static TableColumn column(String table, String name, String type, int size, String charset,
                                      String collation) {
        return new TableColumn("app_main", table, name, charset == null ? JDBCType.BIGINT : JDBCType.VARCHAR, type,
            size, 0, 10, null, null, size, 1, "YES", "NO", "NO", charset, collation);
    }
}
