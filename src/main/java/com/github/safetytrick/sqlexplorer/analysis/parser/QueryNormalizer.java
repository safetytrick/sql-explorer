package com.github.safetytrick.sqlexplorer.analysis.parser;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.StatementVisitorAdapter;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SelectItemVisitorAdapter;
import net.sf.jsqlparser.statement.select.SelectVisitorAdapter;
import org.springframework.stereotype.Component;

@Component
public class QueryNormalizer {
    public String normalize(Statement sql) {
        NormalizerVisitor visitor = new NormalizerVisitor();
        sql.accept(visitor);
        return sql.toString();
    }

    private static class NormalizerVisitor extends StatementVisitorAdapter<Void> {

        @Override
        public <S> Void visit(Statements stmts, S context) {
            for (Statement statement : stmts) {
                statement.accept(this);
            }
            return null;
        }

        @Override
        public <S> Void visit(Select select, S context) {
            select.accept(new SelectNormalizer(), context);
            return null;
        }
    }

    private static class SelectNormalizer extends SelectVisitorAdapter<Void> {
        @Override
        public <S> Void visit(PlainSelect plainSelect, S context) {
            if (plainSelect.getSelectItems() != null) {
                SelectItemNormalizer itemNormalizer = new SelectItemNormalizer();
                for (SelectItem<?> item : plainSelect.getSelectItems()) {
                    item.accept(itemNormalizer, context);
                }
            }
            return null;
        }
    }

    private static class SelectItemNormalizer extends SelectItemVisitorAdapter<Void> {
        @Override
        public <S> Void visit(SelectItem<? extends Expression> selectItem, S context) {
            selectItem.setAlias(null);
            return null;
        }
    }
}
