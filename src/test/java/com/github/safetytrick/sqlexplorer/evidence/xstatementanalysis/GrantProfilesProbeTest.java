package com.github.safetytrick.sqlexplorer.evidence.xstatementanalysis;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.github.safetytrick.sqlexplorer.analysis.IgnoredSchemas;
import com.github.safetytrick.sqlexplorer.schema.ColumnEncodingService;
import com.github.safetytrick.sqlexplorer.schema.CreateStatementService;
import com.github.safetytrick.sqlexplorer.schema.ForeignKeyLoadingService;
import com.github.safetytrick.sqlexplorer.schema.IndexLoadingService;
import com.github.safetytrick.sqlexplorer.schema.LoadedSchema;
import com.github.safetytrick.sqlexplorer.schema.SchemaRepositoryFactory;
import com.github.safetytrick.sqlexplorer.schema.TableColumn;
import com.github.safetytrick.sqlexplorer.schema.TableColumnService;
import com.github.safetytrick.sqlexplorer.schema.TableStructure;
import com.github.safetytrick.sqlexplorer.schema.ViewDefinitionService;

@EnabledIfEnvironmentVariable(named = "GRANTS_PROBE_URL", matches = ".+")
class GrantProfilesProbeTest {

    private static final List<String> ACCOUNTS = List.of(
        "p_select", "p_showview", "p_showview_global", "p_select_showview", "p_references", "p_references_showview");

    private final IgnoredSchemas ignoredSchemas = new IgnoredSchemas();
    private final SchemaRepositoryFactory factory = new SchemaRepositoryFactory(
        new TableColumnService(new ColumnEncodingService()),
        new IndexLoadingService(),
        new ForeignKeyLoadingService(),
        new CreateStatementService(ignoredSchemas),
        new ViewDefinitionService());

    @Test
    void report() {
        String url = System.getenv("GRANTS_PROBE_URL");
        StringBuilder out = new StringBuilder("\n");
        for (String account : ACCOUNTS) {
            DriverManagerDataSource dataSource = new DriverManagerDataSource(url, account, "pw");
            out.append("=== ").append(account).append('\n');
            probe(out, dataSource, new XStatementAnalysisSqlSource(account, url, account, "pw", Clock.systemUTC()));
        }
        System.out.println(out);
    }

    private void probe(StringBuilder out, DriverManagerDataSource dataSource, XStatementAnalysisSqlSource capture) {
        LoadedSchema schema;
        try {
            schema = factory.load(dataSource);
        } catch (RuntimeException e) {
            out.append("  schema load FAILED: ").append(rootMessage(e)).append('\n');
            return;
        }
        List<TableStructure> visible = schema.getTables().stream()
            .filter(t -> !ignoredSchemas.contains(t.schema()))
            .toList();
        out.append("  tables: ").append(visible.stream()
            .map(t -> t.schema() + "." + t.name() + "(" + t.type() + ")").collect(Collectors.joining(", ")))
            .append('\n');
        for (TableStructure table : visible) {
            out.append("  ").append(table.schema()).append('.').append(table.name()).append(": ")
                .append(table.columns() == null ? "?" : table.columns().size()).append(" cols")
                .append(", ").append(table.indices() == null ? "?" : table.indices().size()).append(" idx")
                .append(", ").append(table.foreignKeys() == null ? "?" : table.foreignKeys().size()).append(" fk")
                .append(", remarks=").append(quote(table.remarks()))
                .append(", colRemarks=").append(table.columns() == null ? "?" : table.columns().stream()
                    .map(TableColumn::remarks).filter(r -> r != null && !r.isEmpty()).toList());
            if ("VIEW".equalsIgnoreCase(table.type())) {
                out.append(", underlying=").append(table.underlyingTables());
            }
            out.append('\n');
            out.append("    show create: ").append(attempt(() -> {
                String ddl = schema.getCreateStatement(table);
                return ddl == null || ddl.isBlank() ? "(empty)" : "ok, " + ddl.length() + " chars";
            })).append('\n');
        }
        out.append("  VIEWS.VIEW_DEFINITION: ").append(attempt(() -> scalar(dataSource,
            "SELECT CONCAT('[', VIEW_DEFINITION, ']') FROM information_schema.VIEWS "
                + "WHERE TABLE_SCHEMA='app' AND TABLE_NAME='customer_orders'"))).append('\n');
        out.append("  SELECT app.customer: ").append(attempt(() -> scalar(dataSource,
            "SELECT email FROM app.customer LIMIT 1"))).append('\n');
        out.append("  SELECT other.secret: ").append(attempt(() -> scalar(dataSource,
            "SELECT COUNT(*) FROM other.secret"))).append('\n');
        out.append("  x$statement_analysis: ").append(attempt(() ->
            capture.getSql().count() + " rows, context " + capture.getCaptureServerContext().keySet()))
            .append('\n');
        out.append("  EXPLAIN table: ").append(attempt(() -> explain(dataSource,
            "SELECT * FROM app.orders WHERE customer_id = 1"))).append('\n');
        out.append("  EXPLAIN view: ").append(attempt(() -> explain(dataSource,
            "SELECT * FROM app.customer_orders"))).append('\n');
    }

    private static String explain(DriverManagerDataSource dataSource, String sql) throws SQLException {
        String plan = scalar(dataSource, "EXPLAIN FORMAT=JSON " + sql);
        return plan == null ? "(no plan)" : "ok, " + plan.length() + " chars";
    }

    private static String scalar(DriverManagerDataSource dataSource, String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? resultSet.getString(1) : "(no rows)";
        }
    }

    private interface Probe {
        String run() throws Exception;
    }

    private static String attempt(Probe probe) {
        try {
            return probe.run();
        } catch (Exception e) {
            return "REFUSED: " + rootMessage(e);
        }
    }

    private static String rootMessage(Throwable e) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable t = e; t != null && !chain.contains(t); t = t.getCause()) {
            chain.add(t);
        }
        return chain.getLast().getMessage();
    }

    private static String quote(String s) {
        return s == null ? "null" : "'" + s + "'";
    }
}
