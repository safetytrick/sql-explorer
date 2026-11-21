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

public final class SchemaFixture {

    private final LoadedSchema schema;
    private final ColumnTableResolver resolver;

    private SchemaFixture(LoadedSchema schema) {
        this.schema = schema;
        this.resolver = new ColumnTableResolver(schema);
    }

    public static SchemaFixture fromSql(String ddl) throws JSQLParserException {
        Statements statements = CCJSqlParserUtil.parseStatements(ddl);
        List<TableStructure> structures = new ArrayList<>();
        for (Statement statement : statements) {
            structures.add(TableStructure.fromCreateTable(statement.toString(), TestSchemas.DEFAULT_SCHEMA).build());
        }
        return new SchemaFixture(new LoadedSchema(structures));
    }

    public static SchemaFixture fromResources(String... tableNames) {
        return new SchemaFixture(TestSchemas.of(tableNames));
    }

    public static SchemaFixture allFixtures() {
        return new SchemaFixture(TestSchemas.all());
    }

    public LoadedSchema schema() {
        return schema;
    }

    public ColumnTableResolver resolver() {
        return resolver;
    }

    public SqlAnalysisService service(SqlAnalyzer... analyzers) {
        return new SqlAnalysisService(analyzers);
    }

    public SqlAnalysisService analysisServiceWithSchemaInference() {
        return service(
            new StatementTypeAnalyzer(),
            new WriteTablesAnalyzer(),
            new TableNameAnalyzer(),
            new SchemaAnalyzer(),
            new JoinColumnAnalyzer(resolver),
            new WhereClauseAnalyzer(resolver),
            new SelectColumnAnalyzer(resolver),
            new IndexHintAnalyzer(),
            new GeneratedSqlAnalyzer(),
            new UnknownSchemaAnalyzer());
    }

    public SqlProfile analyze(String sql) throws JSQLParserException {
        SqlAnalysisService service = service(
            new StatementTypeAnalyzer(),
            new WriteTablesAnalyzer(),
            new TableNameAnalyzer(),
            new SchemaAnalyzer(),
            new JoinColumnAnalyzer(resolver),
            new WhereClauseAnalyzer(resolver),
            new SelectColumnAnalyzer(resolver),
            new IndexHintAnalyzer(),
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
