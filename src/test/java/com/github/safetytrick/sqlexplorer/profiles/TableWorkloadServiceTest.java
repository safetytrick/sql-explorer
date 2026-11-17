package com.github.safetytrick.sqlexplorer.profiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.avro.QueryActivityStatus;
import com.github.safetytrick.sqlexplorer.avro.RuntimeMetrics;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.evidence.SqlRepositoryService;
import com.github.safetytrick.sqlexplorer.profiles.TableWorkloadService.LockRisk;
import com.github.safetytrick.sqlexplorer.profiles.TableWorkloadService.LockStatement;
import com.github.safetytrick.sqlexplorer.profiles.TableWorkloadService.WriteRate;
import com.github.safetytrick.sqlexplorer.profiles.TableWorkloadService.WriteStatement;
import com.github.safetytrick.sqlexplorer.profiles.TableWorkloadService.WriteTypeTotal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TableWorkloadServiceTest {

    private static final TableReference ACCOUNT = TableReference.newTableReference("app_main", "account");
    private static final TableReference SITE = TableReference.newTableReference("app_main", "site");
    private static final LocalDateTime START = LocalDateTime.of(2026, 5, 1, 0, 0);

    private SqlRepositoryService sqlRepositoryService;
    private TableWorkloadService service;

    @BeforeEach
    void setUp() {
        sqlRepositoryService = mock(SqlRepositoryService.class);
        service = new TableWorkloadService(sqlRepositoryService);
    }

    @Test
    void writeRateAveragesEachStatementOverItsOwnWindow() {
        when(sqlRepositoryService.getTableQueries(ACCOUNT)).thenAnswer(inv -> Stream.of(
            // 1,000 inserts over 10 days: 100 a day
            profile("ins", "INSERT", metrics(1_000, 1_000, START, START.plusDays(10), 1, 2), ACCOUNT),
            // a statement seen for six hours is floored at a day, not extrapolated to 4x
            profile("upd", "update", metrics(60, 600, START.plusDays(9), START.plusDays(9).plusHours(6), 1, 2),
                ACCOUNT, SITE),
            // 300 deletes over 30 days: 10 a day
            profile("del", "DELETE", metrics(300, 30, START, START.plusDays(30), 1, 2), ACCOUNT),
            profile("sel", "SELECT", metrics(9_999, 0, START, START.plusDays(1), 1, 2), ACCOUNT),
            profile("unmetered", "INSERT", null, ACCOUNT)));

        WriteRate rate = service.getWriteRate(ACCOUNT);

        assertThat(rate.executions()).isEqualTo(1_360);
        assertThat(rate.rowsAffected()).isEqualTo(1_630);
        assertThat(rate.executionsPerDay()).isCloseTo(100 + 60 + 10, within(0.001));
        assertThat(rate.rowsPerDay()).isCloseTo(100 + 600 + 1, within(0.001));
        assertThat(rate.from()).isEqualTo(START);
        assertThat(rate.to()).isEqualTo(START.plusDays(30));
        assertThat(rate.statements())
            .extracting(WriteStatement::digest, WriteStatement::type, WriteStatement::multiTable)
            .containsExactly(
                tuple("ins", "INSERT", false),
                tuple("upd", "UPDATE", true),
                tuple("del", "DELETE", false));
        assertThat(rate.statements().get(1).windowDays()).isCloseTo(0.25, within(0.001));
        assertThat(rate.byType())
            .extracting(WriteTypeTotal::type, WriteTypeTotal::statements, WriteTypeTotal::executions)
            .containsExactly(tuple("INSERT", 1, 1_000L), tuple("UPDATE", 1, 60L), tuple("DELETE", 1, 300L));
    }

    @Test
    void lockRiskRanksByMaxTimeAndCountsTheLongOnes() {
        when(sqlRepositoryService.getTableQueries(ACCOUNT)).thenAnswer(inv -> Stream.of(
            profile("quick", "SELECT", metrics(10_000, 0, START, START.plusDays(10), 2, 40), ACCOUNT),
            profile("report", "SELECT", metrics(20, 0, START, START.plusDays(10), 9_000, 45_000), ACCOUNT),
            profile("batch", "UPDATE", metrics(100, 0, START, START.plusDays(10), 300, 1_500), ACCOUNT)));

        LockRisk risk = service.getLockRisk(ACCOUNT);

        assertThat(risk.statementCount()).isEqualTo(3);
        assertThat(risk.longStatements()).isEqualTo(2);
        assertThat(risk.longExecutionsPerDay()).isCloseTo(2 + 10, within(0.001));
        assertThat(risk.maxTimeMs()).isCloseTo(45_000, within(0.001));
        assertThat(risk.maxAverageTimeMs()).isCloseTo(9_000, within(0.001));
        assertThat(risk.quietHoursAvailable()).isFalse();
        assertThat(risk.statements()).extracting(LockStatement::digest)
            .containsExactly("report", "batch", "quick");
    }

    /**
     * A statement is charged only to the tables it writes (sqlx-n5j.16): an
     * INSERT … SELECT reading the site table is not the site table's write.
     * One whose written tables are unknown still counts against every table.
     */
    @Test
    void writesCountOnlyAgainstTheTablesWritten() {
        SqlProfile copy = profile("copy", "INSERT", metrics(500, 500, START, START.plusDays(10), 1, 2), ACCOUNT, SITE);
        copy.setWriteTables(List.of(ACCOUNT));
        SqlProfile rename = profile("rename", "UPDATE", metrics(100, 100, START, START.plusDays(10), 1, 2), SITE, ACCOUNT);
        rename.setWriteTables(List.of(SITE));
        SqlProfile unparsed = profile("unparsed", "UNKNOWN", metrics(30, 30, START, START.plusDays(10), 1, 2), SITE, ACCOUNT);
        when(sqlRepositoryService.getTableQueries(SITE)).thenAnswer(inv -> Stream.of(copy, rename, unparsed));

        WriteRate rate = service.getWriteRate(SITE);

        assertThat(rate.statements()).extracting(WriteStatement::digest, WriteStatement::multiTable)
            .containsExactlyInAnyOrder(tuple("rename", false), tuple("unparsed", true));
        assertThat(rate.executions()).isEqualTo(130);
    }

    /** The table lens relies on zeros, not an error, for a table nothing knows. */
    @Test
    void anUnknownTableAnswersZeros() {
        when(sqlRepositoryService.getTableQueries(ACCOUNT)).thenAnswer(inv -> Stream.empty());

        WriteRate rate = service.getWriteRate(ACCOUNT);
        LockRisk risk = service.getLockRisk(ACCOUNT);

        assertThat(rate.executions()).isZero();
        assertThat(rate.statements()).isEmpty();
        assertThat(rate.from()).isNull();
        assertThat(risk.statementCount()).isZero();
        assertThat(risk.maxTimeMs()).isZero();
        assertThat(risk.statements()).isEmpty();
    }

    private static RuntimeMetrics metrics(long executions, long rowsAffected, LocalDateTime firstSeen,
                                          LocalDateTime lastSeen, double avgMs, double maxMs) {
        return new RuntimeMetrics(false, executions, 0L, 0L,
            picos(avgMs * executions), picos(maxMs), picos(avgMs),
            0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
            "digest", lastSeen, firstSeen, BigDecimal.ZERO, rowsAffected, 0L, List.of());
    }

    private static BigDecimal picos(double millis) {
        return BigDecimal.valueOf(millis).multiply(BigDecimal.valueOf(1_000_000_000L));
    }

    private static SqlProfile profile(String digest, String type, RuntimeMetrics metrics, TableReference... tables) {
        SqlProfile profile = new SqlProfile();
        profile.setId(digest);
        profile.setQuery(type.toLowerCase() + " account");
        profile.setType(type);
        profile.setDigest(digest);
        profile.setNormalizedDigest(digest);
        profile.setSchemaName("app_main");
        profile.setQueryActivityStatus(QueryActivityStatus.UNKNOWN);
        profile.setRuntimeMetrics(metrics);
        profile.setTables(List.of(tables));
        return profile;
    }
}
