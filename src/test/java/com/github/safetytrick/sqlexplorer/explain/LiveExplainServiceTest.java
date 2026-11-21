package com.github.safetytrick.sqlexplorer.explain;

import java.util.List;

import com.github.safetytrick.sqlexplorer.environment.Environment;
import com.github.safetytrick.sqlexplorer.environment.EnvironmentContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LiveExplainServiceTest {

    @Test
    void refusesAnEnvironmentThatHasNotOptedIn() {
        LiveExplainService service = serviceFor(new Environment("regression", List.of()));

        LiveExplainService.Result result = service.explain("select 1", "probe");

        assertThat(result.succeeded()).isFalse();
        assertThat(result.failure()).isEqualTo(LiveExplainService.Failure.NOT_ENABLED);
        assertThat(result.detail()).contains("explainEnabled");
    }

    @Test
    void refusesACachedEnvironmentWithoutConnecting() {
        Environment environment = new Environment("offline-dummy", List.of(), true);
        environment.setCached(true);

        LiveExplainService.Result result = serviceFor(environment).explain("select 1", "probe");

        assertThat(result.failure()).isEqualTo(LiveExplainService.Failure.OFFLINE);
        assertThat(result.detail()).contains("pull it to go live");
    }

    @Test
    void theTwoArgumentEnvironmentConstructorDefaultsToOff() {
        assertThat(new Environment("regression", List.of()).isExplainEnabled()).isFalse();
    }

    @Test
    void getsPastTheOptInOnlyWhenItIsSet() {
        LiveExplainService service = serviceFor(new Environment("regression", List.of(), true));

        LiveExplainService.Result result = service.explain("select 1", "probe");

        assertThat(result.failure()).isEqualTo(LiveExplainService.Failure.NO_SERVER);
    }

    /**
     * EXPLAIN ANALYZE executes the statement rather than planning it — measured
     * on MySQL 8.4.4, it reports "actual time=". It is the one thing this
     * feature must never do, so it is refused however it is spelled.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "explain analyze select id from orders",
        "EXPLAIN ANALYZE SELECT id FROM orders",
        "  Explain   Analyze  select 1",
        "analyze table orders",
        "select id from orders /* analyze */",
    })
    void refusesAnythingThatWouldExecuteRatherThanPlan(String sql) {
        assertThat(LiveExplainService.assertExplainable(sql))
            .as("must refuse: %s", sql)
            .isNotNull();
    }

    @Test
    void refusesAStatementThatIsAlreadyAnExplain() {
        assertThat(LiveExplainService.assertExplainable("explain select 1"))
            .contains("already an EXPLAIN");
    }

    @Test
    void refusesMoreThanOneStatement() {
        assertThat(LiveExplainService.assertExplainable("select 1; drop table orders"))
            .contains("more than one statement");
        assertThat(LiveExplainService.assertExplainable("select 1; delete from orders where 1=1"))
            .isNotNull();
    }

    @Test
    void allowsATrailingSemicolon() {
        assertThat(LiveExplainService.assertExplainable("select id from orders;")).isNull();
    }

    @Test
    void refusesDigestTextAndSaysWhatToDoInstead() {
        String refusal = LiveExplainService.assertExplainable("select id from orders where total > ?");

        assertThat(refusal).contains("placeholders").contains("example");
    }

    @Test
    void refusesNothingAtAll() {
        assertThat(LiveExplainService.assertExplainable(null)).isNotNull();
        assertThat(LiveExplainService.assertExplainable("   ")).isNotNull();
    }

    @Test
    void allowsAnOrdinaryRunnableStatement() {
        assertThat(LiveExplainService.assertExplainable("select id from orders where total > 100")).isNull();
        assertThat(LiveExplainService.assertExplainable(
            "update orders set total = 1 where id = 2")).isNull();
    }

    @Test
    void timeBoxesEveryStatement() {
        assertThat(LiveExplainService.QUERY_TIMEOUT_SECONDS).isBetween(1, 30);
    }

    private static LiveExplainService serviceFor(Environment environment) {
        EnvironmentContext context = mock(EnvironmentContext.class);
        when(context.current()).thenReturn(environment);
        return new LiveExplainService(context);
    }
}
