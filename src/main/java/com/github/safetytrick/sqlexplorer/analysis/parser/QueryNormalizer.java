package com.github.safetytrick.sqlexplorer.analysis.parser;

import net.sf.jsqlparser.statement.Commit;
import net.sf.jsqlparser.statement.SetStatement;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.StatementVisitorAdapter;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.UseStatement;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.create.index.CreateIndex;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.create.view.AlterView;
import net.sf.jsqlparser.statement.create.view.CreateView;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.drop.Drop;
import net.sf.jsqlparser.statement.execute.Execute;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.merge.Merge;
import net.sf.jsqlparser.statement.replace.Replace;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectExpressionItem;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SelectItemVisitor;
import net.sf.jsqlparser.statement.select.SelectVisitor;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.statement.truncate.Truncate;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.upsert.Upsert;
import org.springframework.stereotype.Component;

@Component
public class QueryNormalizer {
    public String normalize(Statement sql) {
        NormalizerVisitor visitor = new NormalizerVisitor();
        sql.accept(visitor);
        return sql.toString();
    }

    private static class NormalizerVisitor extends StatementVisitorAdapter implements SelectVisitor, SelectItemVisitor {

        @Override
        public void visit(Commit commit) { }

        @Override
        public void visit(Delete delete) { }

        @Override
        public void visit(Update update) { }

        @Override
        public void visit(Insert insert) { }

        @Override
        public void visit(Replace replace) { }

        @Override
        public void visit(Drop drop) { }

        @Override
        public void visit(Truncate truncate) { }

        @Override
        public void visit(CreateIndex createIndex) { }

        @Override
        public void visit(CreateTable createTable) { }

        @Override
        public void visit(CreateView createView) { }

        @Override
        public void visit(AlterView alterView) { }

        @Override
        public void visit(Alter alter) { }

        @Override
        public void visit(Statements stmts) {
            if (stmts.getStatements() != null) {
                for (Statement statement : stmts.getStatements()) {
                    statement.accept(this);
                }
            }

        }

        @Override
        public void visit(Execute execute) { }

        @Override
        public void visit(SetStatement set) { }

        @Override
        public void visit(Merge merge) { }

        @Override
        public void visit(Select select) {
            select.getSelectBody().accept(this);
        }

        @Override
        public void visit(Upsert upsert) { }

        @Override
        public void visit(UseStatement use) { }

        @Override
        public void visit(PlainSelect plainSelect) {
            if (plainSelect.getSelectItems() != null) {
                for (SelectItem item : plainSelect.getSelectItems()) {
                    item.accept(this);
                }
            }

        }

        @Override
        public void visit(SetOperationList setOpList) { }

        @Override
        public void visit(WithItem withItem) { }

        @Override
        public void visit(AllColumns allColumns) { }

        @Override
        public void visit(AllTableColumns allTableColumns) { }

        @Override
        public void visit(SelectExpressionItem selectExpressionItem) {
            selectExpressionItem.setAlias(null);
        }
    }
}
