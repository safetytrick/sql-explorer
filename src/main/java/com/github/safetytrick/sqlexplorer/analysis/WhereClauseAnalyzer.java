package com.github.safetytrick.sqlexplorer.analysis;

import com.github.safetytrick.sqlexplorer.analysis.resolver.ColumnTableResolver;
import com.github.safetytrick.sqlexplorer.analysis.resolver.ColumnTableResolverFactory;
import com.github.safetytrick.sqlexplorer.analysis.resolver.SchemaAwareTableAliasResolver;
import com.github.safetytrick.sqlexplorer.analysis.resolver.SimpleColumnAliasResolver;
import com.github.safetytrick.sqlexplorer.analysis.resolver.TableAliasResolver;
import com.github.safetytrick.sqlexplorer.avro.ColumnReference;
import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.schema.SqlSchemaRepository;
import jakarta.inject.Inject;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ExistsExpression;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.StatementVisitorAdapter;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.FromItemVisitorAdapter;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.LateralSubSelect;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
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

@Order(3)
@Component
class WhereClauseAnalyzer implements SqlAnalyzer {

    private final ColumnTableResolverFactory resolverFactory;

    @Inject
    public WhereClauseAnalyzer(ColumnTableResolverFactory resolverFactory) {
        this.resolverFactory = resolverFactory;
    }

    /** Test/convenience: resolve against a fixed resolver regardless of schema. */
    public WhereClauseAnalyzer(ColumnTableResolver columnTableResolver) {
        this.resolverFactory = schema -> columnTableResolver;
    }

    public WhereClauseAnalyzer() {
        this(ColumnTableResolver.unsupported());
    }

    @Override
    public void analyze(Statement statement, SqlProfile.Builder builder, SqlSchemaRepository schema) {
        new WhereClauseVisitor(builder, resolverFactory.forSchema(schema)).visit(statement);
    }

    private class WhereClauseVisitor extends StatementVisitorAdapter<Void> {
        private final SqlProfile.Builder builder;
        private final ColumnTableResolver columnTableResolver;
        private TableAliasResolver aliasResolver;
        private final WhereSelectVisitor selectVisitor = new WhereSelectVisitor();
        private final java.util.Set<String> cteNames = new java.util.HashSet<>();

        private WhereClauseVisitor(SqlProfile.Builder builder, ColumnTableResolver columnTableResolver) {
            super();
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
            TableAliasResolver previousAliasResolver = aliasResolver;
            aliasResolver = new SchemaAwareTableAliasResolver(select, columnTableResolver);
            select.accept(selectVisitor, context);
            aliasResolver = previousAliasResolver;
            return null;
        }


        @Override
        public <S> Void visit(Delete delete, S context) {
            selectVisitor.visitWhere(delete.getWhere());
            return null;
        }

        @Override
        public <S> Void visit(Update update, S context) {
            selectVisitor.visitWhere(update.getWhere());
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

        /**
         * Visits select bodies. Joins are delegated to {@link WhereFromItemVisitor};
         * subqueries appearing as expressions are routed back here via
         * {@link ExpressionVisitorAdapter#setSelectVisitor}.
         */
        private class WhereSelectVisitor extends SelectVisitorAdapter<Void> {
            private final WhereFromItemVisitor fromItemVisitor = new WhereFromItemVisitor();

            @Override
            public <S> Void visit(PlainSelect plainSelect, S context) {
                if (plainSelect.getWhere() != null) {
                    plainSelect.getWhere().accept(new WhereExpressionVisitor());
                }
                if (plainSelect.getJoins() != null) {
                    for (Join join : plainSelect.getJoins()) {
                        join.getRightItem().accept(fromItemVisitor);
                    }
                }
                return null;
            }

            private void visitWhere(Expression where) {
                if (where != null) {
                    where.accept(new WhereExpressionVisitor());
                }
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
                throw new UnsupportedSqlException("with item not supported");
            }

            @Override
            public <S> Void visit(ParenthesedSelect parenthesedSelect, S context) {
                TableAliasResolver previous = aliasResolver;
                aliasResolver = new SchemaAwareTableAliasResolver(null, parenthesedSelect, columnTableResolver);
                parenthesedSelect.getSelect().accept(this, context);
                aliasResolver = previous;
                return null;
            }

            private class WhereFromItemVisitor extends FromItemVisitorAdapter<Void> {
                @Override
                public <S> Void visit(ParenthesedSelect parenthesedSelect, S context) {
                    return WhereSelectVisitor.this.visit(parenthesedSelect, context);
                }

                @Override
                public <S> Void visit(LateralSubSelect lateralSubSelect, S context) {
                    throw new UnsupportedSqlException("lateral subselect not supported");
                }
            }

            private class WhereExpressionVisitor extends ExpressionVisitorAdapter<Void> {

                private WhereExpressionVisitor() {
                    super();
                    setSelectVisitor(WhereSelectVisitor.this);
                }

                @Override
                public <S> Void visit(Column column, S context) {
                    // skip the constant true, not sure how this gets into valid sql but it
                    // shouldn't have been parsed as a column to column reference
                    if (column.getTable() == null && "TRUE".equals(column.getColumnName())) {
                        return null;
                    }
                    String tableName = aliasResolver.resolveTableName(column);
                    if (WithItems.isCte(cteNames, tableName)) {
                        return null;
                    }

                    builder.criteriaColumn(ColumnReference.fromComponents(null, tableName, column.getColumnName()));
                    return null;
                }

                @Override
                public <S> Void visit(ParenthesedSelect parenthesedSelect, S context) {
                    TableAliasResolver previous = aliasResolver;

                    // todo this won't be able to delegate back to the parent if necessary I think that will fail for some sql
                    aliasResolver = new SchemaAwareTableAliasResolver(previous, parenthesedSelect, columnTableResolver);
                    super.visit(parenthesedSelect, context);
                    aliasResolver = previous;
                    return null;
                }

                @Override
                public <S> Void visit(SelectItem<? extends Expression> selectItem, S context) {
                    return super.visit(selectItem, context);
                }

                @Override
                public <S> Void visit(EqualsTo equalsTo, S context) {
                    super.visit(equalsTo, context);

                    SimpleColumnAliasResolver visitor = new SimpleColumnAliasResolver(aliasResolver);
                    String leftColumn = visitor.columnReference(equalsTo.getLeftExpression());
                    String rightColumn = visitor.columnReference(equalsTo.getRightExpression());

                    if (leftColumn != null && rightColumn != null) {
                        builder.joinPair(leftColumn, rightColumn);
                    }
                    return null;
                }

                @Override
                public <S> Void visit(ExistsExpression expr, S context) {
                    return super.visit(expr, context);
                }
            }
        }
    }
}
