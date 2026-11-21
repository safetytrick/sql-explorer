package com.github.safetytrick.sqlexplorer.analysis;

import java.util.Locale;
import java.util.Set;

import com.github.safetytrick.sqlexplorer.avro.IndexHint;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.schema.SqlSchemaRepository;
import net.sf.jsqlparser.expression.MySQLIndexHint;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Order(5)
@Component
class IndexHintAnalyzer implements SqlAnalyzer {

    @Override
    public void analyze(Statement statement, SqlProfile.Builder builder, SqlSchemaRepository schema) {
        Set<String> cteNames = statement instanceof Select select ? WithItems.namesOf(select) : Set.of();
        new TablesNamesFinder<Void>() {
            @Override
            public <S> Void visit(Table table, S context) {
                super.visit(table, context);
                MySQLIndexHint hint = table.getIndexHint();
                if (hint == null || hint.getIndexNames() == null || WithItems.isCte(cteNames, table.getName())) {
                    return null;
                }
                TableReference reference = TableReference.newTableReference(table.getSchemaName(), table.getName());
                String action = hint.getAction().toUpperCase(Locale.ROOT);
                for (String index : hint.getIndexNames()) {
                    builder.indexHint(IndexHint.newBuilder()
                        .setTable(reference)
                        .setIndex(index)
                        .setNormalizedIndex(NameNormalizer.normalize(index))
                        .setAction(action)
                        .build());
                }
                return null;
            }
        }.getTables(statement);
    }
}
