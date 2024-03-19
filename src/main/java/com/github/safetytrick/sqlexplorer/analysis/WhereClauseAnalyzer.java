package com.github.safetytrick.sqlexplorer.analysis;

import com.github.safetytrick.sqlexplorer.analysis.resolver.ColumnTableResolver;
import com.github.safetytrick.sqlexplorer.analysis.resolver.SchemaAwareTableAliasResolver;
import com.github.safetytrick.sqlexplorer.analysis.resolver.SimpleColumnAliasResolver;
import com.github.safetytrick.sqlexplorer.analysis.resolver.TableAliasResolver;
import com.github.safetytrick.sqlexplorer.model.ColumnReference;
import com.github.safetytrick.sqlexplorer.model.SqlProfile;
import jakarta.inject.Inject;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ExistsExpression;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.StatementVisitorAdapter;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.replace.Replace;
import net.sf.jsqlparser.statement.select.FromItemVisitorAdapter;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.LateralSubSelect;
import net.sf.jsqlparser.statement.select.ParenthesisFromItem;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectBody;
import net.sf.jsqlparser.statement.select.SelectExpressionItem;
import net.sf.jsqlparser.statement.select.SelectVisitor;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.SubJoin;
import net.sf.jsqlparser.statement.select.SubSelect;
import net.sf.jsqlparser.statement.select.TableFunction;
import net.sf.jsqlparser.statement.select.ValuesList;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.upsert.Upsert;
import net.sf.jsqlparser.statement.values.ValuesStatement;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Order(3)
@Component
class WhereClauseAnalyzer implements SqlAnalyzer {

    private final ColumnTableResolver columnTableResolver;

    @Inject
    public WhereClauseAnalyzer(ColumnTableResolver columnTableResolver) {
        this.columnTableResolver = columnTableResolver;
    }

    public WhereClauseAnalyzer() {
        this(ColumnTableResolver.unsupported());
    }

    @Override
    public void analyze(Statement statement, SqlProfile.Builder builder) {
        new WhereClauseVisitor(builder).visit(statement);
    }

    private class WhereClauseVisitor extends StatementVisitorAdapter {
        private final SqlProfile.Builder builder;
        private TableAliasResolver aliasResolver;
        private final WhereColumnColumnSelectVisitor visitor = new WhereColumnColumnSelectVisitor();

        private WhereClauseVisitor(SqlProfile.Builder builder) {
            super();
            this.builder = builder;
        }

        void visit(Statement statement) {
            aliasResolver = new SchemaAwareTableAliasResolver(statement, columnTableResolver);
            statement.accept(this);
        }

        @Override
        public void visit(Select select) {
            if (select.getWithItemsList() != null && !select.getWithItemsList().isEmpty()) {
                throw new UnsupportedSqlException("item list parsing not supported");
            }
            TableAliasResolver previousAliasResolver = aliasResolver;
            aliasResolver = new SchemaAwareTableAliasResolver(select, columnTableResolver);
            if (select.getSelectBody() != null) {
                select.getSelectBody().accept(visitor);
            }
            aliasResolver = previousAliasResolver;
        }


        @Override
        public void visit(Delete delete) {
            visitor.visitWhere(delete.getWhere());
        }

        @Override
        public void visit(Update update) {
            visitor.visitWhere(update.getWhere());
        }

        @Override
        public void visit(Insert insert) {
            if (insert.getSelect() != null) {
                visit(insert.getSelect());
            }
        }

        @Override
        public void visit(Replace replace) {
            throw new UnsupportedSqlException("replace not supported");
        }

        @Override
        public void visit(Upsert upsert) {
            if (upsert.getSelect() != null) {
                visit(upsert.getSelect());
            }
        }

        private class WhereColumnColumnSelectVisitor extends FromItemVisitorAdapter implements SelectVisitor {
            @Override
            public void visit(PlainSelect plainSelect) {
                if (plainSelect.getWhere() != null) {
                    WhereExpressionVisitor whereExpressionVisitor = new WhereExpressionVisitor();
                    whereExpressionVisitor.setSelectVisitor(this);
                    plainSelect.getWhere().accept(whereExpressionVisitor);
                }
                if (plainSelect.getJoins() != null) {
                    for (Join join : plainSelect.getJoins()) {

                        join.getRightItem().accept(this);

                    }
                }
            }

            private void visitWhere(Expression where) {
                if (where != null) {
                    WhereExpressionVisitor whereExpressionVisitor = new WhereExpressionVisitor();
                    where.accept(whereExpressionVisitor);
                }
            }

            @Override
            public void visit(SetOperationList setOpList) {
                if (setOpList.getSelects() != null) {
                    for (SelectBody selectBody : setOpList.getSelects()) {
                        selectBody.accept(this);
                    }
                }
            }

            @Override
            public void visit(WithItem withItem) {
                throw new UnsupportedSqlException("with item not supported");
            }

            @Override
            public void visit(ValuesStatement aThis) {

            }

            @Override
            public void visit(SubSelect subSelect) {
                TableAliasResolver previous = aliasResolver;
                aliasResolver = new SchemaAwareTableAliasResolver(null, subSelect, columnTableResolver);
                subSelect.getSelectBody().accept(this);
                aliasResolver = previous;


            }

            @Override
            public void visit(SubJoin subjoin) { }

            @Override
            public void visit(LateralSubSelect lateralSubSelect) {
                throw new UnsupportedSqlException("lateral subselect not supported");
            }

            @Override
            public void visit(Table table) {
                super.visit(table);
            }

            @Override
            public void visit(ValuesList valuesList) {
                super.visit(valuesList);
            }

            @Override
            public void visit(TableFunction valuesList) {
                super.visit(valuesList);
            }

            @Override
            public void visit(ParenthesisFromItem aThis) {
                super.visit(aThis);
            }

            private class WhereExpressionVisitor extends ExpressionVisitorAdapter {

                private WhereExpressionVisitor() {
                    super();
                    setSelectVisitor(WhereColumnColumnSelectVisitor.this);
                }

                @Override
                public void visit(Column column) {
                    // skip the constant true, not sure how this gets into valid sql but it
                    // shouldn't have been parsed as a column to column reference
                    if (column.getTable() == null && "TRUE".equals(column.getColumnName())) {
                        return;
                    }
                    String tableName = aliasResolver.resolveTableName(column);

                    builder.criteriaColumn(ColumnReference.fromComponents(null, tableName, column.getColumnName()));
                }

                @Override
                public void visit(SubSelect subSelect) {
                    TableAliasResolver previous = aliasResolver;

                    // todo this won't be able to delegate back to the parent if necessary I think that will fail for some sql
                    aliasResolver = new SchemaAwareTableAliasResolver(previous, subSelect, columnTableResolver);
                    super.visit(subSelect);
                    aliasResolver = previous;
                }

                @Override
                public void visit(SelectExpressionItem selectExpressionItem) {
                    super.visit(selectExpressionItem);
                }

                @Override
                public void visit(EqualsTo equalsTo) {
                    super.visit(equalsTo);

                    SimpleColumnAliasResolver visitor = new SimpleColumnAliasResolver(aliasResolver);
                    String leftColumn = visitor.columnReference(equalsTo.getLeftExpression());
                    String rightColumn = visitor.columnReference(equalsTo.getRightExpression());

                    if (leftColumn != null && rightColumn != null) {
                        builder.joinPair(leftColumn, rightColumn);
                    }
                }

                @Override
                public void visit(ExistsExpression expr) {
                    super.visit(expr);
                }
            }
        }


    }
}
