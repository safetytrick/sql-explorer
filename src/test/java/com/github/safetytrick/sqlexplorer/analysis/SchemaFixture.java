package com.github.safetytrick.sqlexplorer.analysis;

import java.util.ArrayList;
import java.util.List;

import com.github.safetytrick.sqlexplorer.analysis.resolver.ColumnTableResolver;
import com.github.safetytrick.sqlexplorer.avro.QueryActivityStatus;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.schema.LoadedSchema;
import com.github.safetytrick.sqlexplorer.schema.TableStructure;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;

/**
 * Parser-test entry point: build a schema from SQL, then analyze queries
 * against it and assert on the resulting {@link SqlProfile}.
 *
 * <pre>
 * SchemaFixture fixture = SchemaFixture.fromSql("""
 *     create table foo (id bigint not null, bar_id bigint, primary key (id));
 *     create table bar (id bigint not null, name varchar(64), primary key (id));
 *     """);
 * SqlProfile profile = fixture.analyze("select f.id, b.name from foo f join bar b on f.bar_id = b.id");
 * </pre>
 *
 * Statements parse with a default schema of {@link TestSchemas#DEFAULT_SCHEMA}
 * unless they name their own. {@code CREATE VIEW} statements are supported
 * (they carry a type and underlying tables, but no columns).
 */
public final class SchemaFixture {

    private final LoadedSchema schema;
    private final ColumnTableResolver resolver;

    private SchemaFixture(LoadedSchema schema) {
        this.schema = schema;
        this.resolver = new ColumnTableResolver(schema);
    }

    /** A schema from one or more semicolon-separated CREATE TABLE / CREATE VIEW statements. */
    public static SchemaFixture fromSql(String ddl) throws JSQLParserException {
        Statements statements = CCJSqlParserUtil.parseStatements(ddl);
        List<TableStructure> structures = new ArrayList<>();
        for (Statement statement : statements) {
            structures.add(TableStructure.fromCreateTable(statement.toString(), TestSchemas.DEFAULT_SCHEMA).build());
        }
        return new SchemaFixture(new LoadedSchema(structures));
    }

    /** A schema from named {@code src/test/resources/schema} fixtures (without the {@code .sql} suffix). */
    public static SchemaFixture fromResources(String... tableNames) {
        return new SchemaFixture(TestSchemas.of(tableNames));
    }

    /** A schema containing every {@code src/test/resources/schema} fixture. */
    public static SchemaFixture allFixtures() {
        return new SchemaFixture(TestSchemas.all());
    }

    public LoadedSchema schema() {
        return schema;
    }

    /** A resolver over this schema, for analyzers that take one at construction. */
    public ColumnTableResolver resolver() {
        return resolver;
    }

    /** An analysis service running exactly the given analyzers (see {@link #resolver()}). */
    public SqlAnalysisService service(SqlAnalyzer... analyzers) {
        return new SqlAnalysisService(analyzers);
    }

    /**
     * Analyze a query with the full analyzer set against this schema and
     * return the completed profile.
     */
    public SqlProfile analyze(String sql) throws JSQLParserException {
        SqlAnalysisService service = service(
            new TableNameAnalyzer(),
            new SchemaAnalyzer(),
            new JoinColumnAnalyzer(resolver),
            new WhereClauseAnalyzer(resolver),
            new SelectColumnAnalyzer(resolver),
            new GeneratedSqlAnalyzer());

        SqlProfile.Builder builder = SqlProfile.newBuilder();
        builder.setQuery(sql)
            .setId("some-id")
            .setType("some-type")
            .setRuntimeMetrics(null)
            .setQueryActivityStatus(QueryActivityStatus.UNKNOWN);
        service.analyzeSql(sql, builder, schema);

        return builder.completeBuild(TestSchemas.DEFAULT_SCHEMA);
    }
}
