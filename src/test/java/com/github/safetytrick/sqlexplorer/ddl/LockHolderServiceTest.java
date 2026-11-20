package com.github.safetytrick.sqlexplorer.ddl;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.github.safetytrick.sqlexplorer.ddl.LockHolderService.Holder;
import com.github.safetytrick.sqlexplorer.ddl.LockHolderService.Reading;
import com.github.safetytrick.sqlexplorer.ddl.LockHolderService.Report;
import com.github.safetytrick.sqlexplorer.ddl.LockHolderService.TableRef;
import com.github.safetytrick.sqlexplorer.environment.ConnectionInfo;
import com.github.safetytrick.sqlexplorer.environment.DatabaseServer;
import com.github.safetytrick.sqlexplorer.environment.Environment;
import com.github.safetytrick.sqlexplorer.environment.ServerRole;
import com.github.safetytrick.sqlexplorer.schema.SchemaRepositoryFactory;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.UncategorizedSQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Who holds metadata locks on the tables a change would lock (sqlx-9gk).
 *
 * <p>The property that matters most is the one the page promises: a server is
 * connected to only when the request names it.
 */
class LockHolderServiceTest {

    private static final List<TableRef> TABLES = List.of(
        new TableRef("app", "account_site"), new TableRef("app", "site_visit"));

    private static DatabaseServer server(String name, Set<ServerRole> roles) {
        return new DatabaseServer(name, new ConnectionInfo("jdbc:mysql://nowhere", "u", "p"), List.of(),
            mock(SchemaRepositoryFactory.class), null, roles);
    }

    private static Holder holder(String table, Long trxSeconds, Long stateSeconds) {
        return new Holder("app", table, "SHARED_READ", "TRANSACTION", "GRANTED", 7L, "reader",
            "10.0.0.1", "Sleep", null, stateSeconds, trxSeconds);
    }

    private final Environment environment = new Environment("prod", List.of(
        server("db-primary", Set.of(ServerRole.PRIMARY)),
        server("db-replica", Set.of(ServerRole.REPLICA))));

    /** Records which servers were read; each answers with the holders given. */
    private static class Scripted extends LockHolderService {
        final List<String> read = new ArrayList<>();
        final List<Holder> answer;

        Scripted(List<Holder> answer) {
            this.answer = answer;
        }

        @Override
        Reading read(DatabaseServer server, List<TableRef> tables) {
            read.add(server.getName());
            return new Reading(answer, null);
        }
    }

    @Test
    void readsOnlyTheServersTheRequestNames() {
        Scripted service = new Scripted(List.of());

        Report report = service.read(environment, TABLES, List.of("db-replica"));

        assertThat(service.read).containsExactly("db-replica");
        assertThat(report.servers()).extracting(LockHolderService.ServerReading::server)
            .containsExactly("db-replica");
        assertThat(report.servers().getFirst().roles()).containsExactly("replica");
    }

    @Test
    void refusesAServerThatIsNotTheEnvironmentsAndReadsNothing() {
        Scripted service = new Scripted(List.of());

        assertThatThrownBy(() -> service.read(environment, TABLES, List.of("db-primary", "elsewhere")))
            .isInstanceOf(LockHolderService.InvalidRequest.class)
            .hasMessageContaining("elsewhere");
        assertThat(service.read).isEmpty();
    }

    @Test
    void refusesARequestNamingNoServerOrNoTable() {
        Scripted service = new Scripted(List.of());

        assertThatThrownBy(() -> service.read(environment, TABLES, List.of()))
            .isInstanceOf(LockHolderService.InvalidRequest.class);
        assertThatThrownBy(() -> service.read(environment, List.of(), List.of("db-primary")))
            .isInstanceOf(LockHolderService.InvalidRequest.class);
        assertThatThrownBy(() -> service.read(environment, List.of(new TableRef("app", " ")),
            List.of("db-primary")))
            .isInstanceOf(LockHolderService.InvalidRequest.class);
        assertThat(service.read).isEmpty();
    }

    @Test
    void aCachedEnvironmentsServerIsReportedNotConnected() {
        DatabaseServer cached = server("db-primary", Set.of(ServerRole.PRIMARY));
        cached.setOffline("prod");
        Environment offline = new Environment("prod", List.of(cached));

        // the real read: getDataSource() must refuse before any pool exists
        Report report = new LockHolderService().read(offline, TABLES, List.of("db-primary"));

        assertThat(report.servers().getFirst().error()).contains("served from its cache");
        assertThat(report.servers().getFirst().holders()).isEmpty();
    }

    @Test
    void oneServerFailingIsReportedAndTheOthersAreStillRead() {
        LockHolderService service = new LockHolderService() {
            @Override
            Reading read(DatabaseServer server, List<TableRef> tables) {
                if (server.getName().equals("db-primary")) {
                    throw new DataAccessResourceFailureException("connection refused");
                }
                return new Reading(List.of(holder("account_site", 5L, 5L)), null);
            }
        };

        Report report = service.read(environment, TABLES, List.of("db-primary", "db-replica"));

        assertThat(report.servers().get(0).error()).contains("connection refused");
        assertThat(report.servers().get(1).error()).isNull();
        assertThat(report.servers().get(1).holders()).hasSize(1);
    }

    @Test
    void theOldestTransactionLeads() {
        Scripted service = new Scripted(List.of(
            holder("account_site", null, 900L),
            holder("site_visit", 40L, 1L),
            holder("account_site", 3600L, 2L)));

        Report report = service.read(environment, TABLES, List.of("db-primary"));

        assertThat(report.servers().getFirst().holders()).extracting(Holder::transactionSeconds)
            .containsExactly(3600L, 40L, null);
    }

    @Test
    void namesAreBoundNotSplicedAndThisSessionIsLeftOut() {
        List<String> statements = new ArrayList<>();
        List<Object[]> arguments = new ArrayList<>();

        LockHolderService.holders(TABLES, (sql, args) -> {
            statements.add(sql);
            arguments.add(args);
            return List.of();
        });

        assertThat(statements.getFirst()).doesNotContain("account_site")
            .contains("information_schema.innodb_trx")
            .contains("connection_id()");
        assertThat(arguments.getFirst()).containsExactly("app", "account_site", "app", "site_visit");
    }

    @Test
    void withoutThePrivilegeForTransactionAgeTheLocksAreStillRead() {
        List<String> statements = new ArrayList<>();

        Reading reading = LockHolderService.holders(TABLES, (sql, args) -> {
            statements.add(sql);
            if (sql.contains("innodb_trx")) {
                throw new UncategorizedSQLException("holders", sql,
                    new SQLException("Access denied; you need the PROCESS privilege", "42000", 1227));
            }
            return List.of(holder("account_site", null, 12L));
        });

        assertThat(statements).hasSize(2);
        assertThat(statements.get(1)).doesNotContain("innodb_trx");
        assertThat(reading.note()).isEqualTo(LockHolderService.WITHOUT_TRX_NOTE);
        assertThat(reading.holders()).hasSize(1);
    }

    @Test
    void aLostConnectionIsNotMistakenForAMissingPrivilege() {
        assertThatThrownBy(() -> LockHolderService.holders(TABLES, (sql, args) -> {
            throw new DataAccessResourceFailureException("connection reset");
        })).isInstanceOf(DataAccessResourceFailureException.class);
    }
}
