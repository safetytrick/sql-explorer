package com.github.safetytrick.sqlexplorer.analysis;

import com.github.safetytrick.sqlexplorer.analysis.resolver.ColumnTableResolver;
import com.github.safetytrick.sqlexplorer.analysis.resolver.ColumnTableResolverFactory;
import com.github.safetytrick.sqlexplorer.analysis.resolver.SchemaAwareTableAliasResolver;
import com.github.safetytrick.sqlexplorer.analysis.resolver.TableAliasResolver;
import java.util.function.Consumer;

import com.github.safetytrick.sqlexplorer.avro.ColumnReference;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.schema.SqlSchemaRepository;
import jakarta.inject.Inject;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.StatementVisitorAdapter;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SelectVisitorAdapter;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.statement.upsert.Upsert;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Captures the columns a query projects (select list — including columns
 * inside function calls) and groups by. {@code SELECT *} contributes nothing;
 * expansion against the schema is a possible follow-up.
 */
@Order(4)
@Component
class SelectColumnAnalyzer implements SqlAnalyzer {

    private final ColumnTableResolverFactory resolverFactory;

    @Inject
    public SelectColumnAnalyzer(ColumnTableResolverFactory resolverFactory) {
        this.resolverFactory = resolverFactory;
    }

    /** Test/convenience: resolve against a fixed resolver regardless of schema. */
    public SelectColumnAnalyzer(ColumnTableResolver columnTableResolver) {
        this.resolverFactory = schema -> columnTableResolver;
    }

    public SelectColumnAnalyzer() {
        this(ColumnTableResolver.unsupported());
    }

    @Override
    public void analyze(Statement statement, SqlProfile.Builder builder, SqlSchemaRepository schema) {
        new SelectColumnVisitor(builder, resolverFactory.forSchema(schema)).visit(statement);
    }

    private static class SelectColumnVisitor extends StatementVisitorAdapter<Void> {
        private final SqlProfile.Builder builder;
        private final java.util.Set<String> cteNames = new java.util.HashSet<>();
        private final ColumnTableResolver columnTableResolver;
        private TableAliasResolver aliasResolver;
        private final ProjectionSelectVisitor selectVisitor = new ProjectionSelectVisitor();

        private SelectColumnVisitor(SqlProfile.Builder builder, ColumnTableResolver columnTableResolver) {
            this.builder = builder;
            this.columnTableResolver = columnTableResolver;
        }

        void visit(Statement statement) {
            aliasResolver = new SchemaAwareTableAliasResolver(statement, columnTableResolver);
            statement.accept(this);
        }

        @Override
        public <S> Void visit(Select select, S context) {
            cteNames.addAll(WithItems.namesOf(select));
            for (Select cte : WithItems.bodiesOf(select)) {
                visit(cte, context);
            }
            TableAliasResolver previous = aliasResolver;
            aliasResolver = new SchemaAwareTableAliasResolver(select, columnTableResolver);
            select.accept(selectVisitor, context);
            aliasResolver = previous;
            return null;
        }

        @Override
        public <S> Void visit(Insert insert, S context) {
            if (insert.getSelect() != null) {
                visit(insert.getSelect(), context);
            }
            return null;
        }

        @Override
        public <S> Void visit(Upsert upsert, S context) {
            if (upsert.getSelect() != null) {
                visit(upsert.getSelect(), context);
            }
            return null;
        }

        private class ProjectionSelectVisitor extends SelectVisitorAdapter<Void> {

            @Override
            public <S> Void visit(PlainSelect plainSelect, S context) {
                // A column qualified by a derived table's alias names a column
                // of whatever that subquery read; without this it resolved to
                // nothing and was recorded against dual (sqlx-03r).
                DerivedTables.register(plainSelect, aliasResolver, columnTableResolver, builder.getSchemaName());
                if (plainSelect.getSelectItems() != null) {
                    ColumnCollector collector = new ColumnCollector(column -> builder.selectColumn(column));
                    for (SelectItem<?> item : plainSelect.getSelectItems()) {
                        if (item.getExpression() != null) {
                            item.getExpression().accept(collector);
                        }
                    }
                }
                if (plainSelect.getGroupBy() != null
                    && plainSelect.getGroupBy().getGroupByExpressionList() != null) {
                    ColumnCollector collector = new ColumnCollector(column -> builder.groupByColumn(column));
                    for (Object expression : plainSelect.getGroupBy().getGroupByExpressionList()) {
                        if (expression instanceof Expression groupByExpression) {
                            groupByExpression.accept(collector);
                        }
                    }
                }
                return null;
            }

            @Override
            public <S> Void visit(SetOperationList setOpList, S context) {
                if (setOpList.getSelects() != null) {
                    for (Select select : setOpList.getSelects()) {
                        select.accept(this, context);
                    }
                }
                return null;
            }

            @Override
            public <S> Void visit(WithItem<?> withItem, S context) {
                throw new UnsupportedSqlException("with item");
            }
        }

        /** Records every column in an expression tree via the given sink. */
        private class ColumnCollector extends ExpressionVisitorAdapter<Void> {
            private final Consumer<ColumnReference> sink;

            private ColumnCollector(Consumer<ColumnReference> sink) {
                this.sink = sink;
            }

            @Override
            public <S> Void visit(Column column, S context) {
                String tableName = aliasResolver.resolveTableName(column);
                if (WithItems.isCte(cteNames, tableName)) {
                    return null;
                }
                sink.accept(ColumnReference.fromComponents(null, tableName, column.getColumnName()));
                return null;
            }
        }
    }
}
