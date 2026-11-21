package com.github.safetytrick.sqlexplorer.analysis;

import com.github.safetytrick.sqlexplorer.analysis.resolver.ColumnTableResolver;
import com.github.safetytrick.sqlexplorer.analysis.resolver.ColumnTableResolverFactory;
import com.github.safetytrick.sqlexplorer.analysis.resolver.SchemaAwareTableAliasResolver;
import com.github.safetytrick.sqlexplorer.analysis.resolver.TableAliasResolver;
import java.util.List;
import java.util.function.Consumer;

import com.github.safetytrick.sqlexplorer.avro.ColumnReference;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.schema.SqlSchemaRepository;
import jakarta.inject.Inject;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.StatementVisitorAdapter;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SelectVisitorAdapter;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.upsert.Upsert;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Order(4)
@Component
class SelectColumnAnalyzer implements SqlAnalyzer {

    private final ColumnTableResolverFactory resolverFactory;

    @Inject
    public SelectColumnAnalyzer(ColumnTableResolverFactory resolverFactory) {
        this.resolverFactory = resolverFactory;
    }

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
        public <S> Void visit(Update update, S context) {
            collectOrderBy(update.getOrderByElements(), List.of());
            return null;
        }

        @Override
        public <S> Void visit(Delete delete, S context) {
            collectOrderBy(delete.getOrderByElements(), List.of());
            return null;
        }

        private void collectOrderBy(List<OrderByElement> orderBy, List<SelectItem<?>> selectItems) {
            if (orderBy == null) {
                return;
            }
            ColumnCollector collector = new ColumnCollector(column -> builder.orderByColumn(column));
            for (OrderByElement element : orderBy) {
                Expression expression = element.getExpression();
                if (expression == null) {
                    continue;
                }
                Expression selected = selectItemFor(expression, selectItems);
                (selected != null ? selected : expression).accept(collector);
            }
        }

        /**
         * MySQL searches the select list before the FROM tables for an
         * unqualified name, so an alias wins over a table column of the same name.
         */
        private static Expression selectItemFor(Expression orderBy, List<SelectItem<?>> selectItems) {
            if (orderBy instanceof LongValue position) {
                long index = position.getValue();
                if (index >= 1 && index <= selectItems.size()) {
                    Expression expression = selectItems.get((int) index - 1).getExpression();
                    return expression instanceof net.sf.jsqlparser.statement.select.AllColumns ? null : expression;
                }
                return null;
            }
            if (orderBy instanceof Column column && column.getTable() == null && column.getColumnName() != null) {
                String name = NameNormalizer.normalize(column.getColumnName());
                for (SelectItem<?> item : selectItems) {
                    if (item.getAlias() != null && item.getAlias().getName() != null
                        && NameNormalizer.normalize(item.getAlias().getName()).equals(name)) {
                        return item.getExpression();
                    }
                }
            }
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
                collectOrderBy(plainSelect.getOrderByElements(),
                    plainSelect.getSelectItems() == null ? List.of() : plainSelect.getSelectItems());
                return null;
            }

            @Override
            public <S> Void visit(SetOperationList setOpList, S context) {
                if (setOpList.getSelects() != null) {
                    for (Select select : setOpList.getSelects()) {
                        select.accept(this, context);
                    }
                    if (setOpList.getOrderByElements() != null && !setOpList.getSelects().isEmpty()
                        && setOpList.getSelects().getFirst() instanceof PlainSelect first) {
                        TableAliasResolver previous = aliasResolver;
                        aliasResolver = new SchemaAwareTableAliasResolver(first, columnTableResolver);
                        collectOrderBy(setOpList.getOrderByElements(),
                            first.getSelectItems() == null ? List.of() : first.getSelectItems());
                        aliasResolver = previous;
                    }
                }
                return null;
            }

            @Override
            public <S> Void visit(WithItem<?> withItem, S context) {
                throw new UnsupportedSqlException("with item");
            }
        }

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
