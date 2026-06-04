package com.github.safetytrick.sqlexplorer.analysis.resolver;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.SetStatement;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.create.index.CreateIndex;
import net.sf.jsqlparser.statement.create.view.AlterView;
import net.sf.jsqlparser.statement.create.view.CreateView;
import net.sf.jsqlparser.statement.drop.Drop;
import net.sf.jsqlparser.statement.execute.Execute;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.truncate.Truncate;
import net.sf.jsqlparser.util.TablesNamesFinder;

class ExtendedTableNamesFinder extends TablesNamesFinder<Void> {
    // not safe for reuse.
    private Map<String, String> aliasMappings = new LinkedHashMap<>();
    private boolean inWhere;

    public Map<String,String> resolveAliases(Statement statement) {
        aliasMappings = new LinkedHashMap<>();
        super.getTableList(statement);
        return aliasMappings;
    }

    public Map<String,String> resolveAliases(Expression expression) {
        aliasMappings = new LinkedHashMap<>();
        init(false);
        expression.accept(this);
        return aliasMappings;
    }

    @Override
    public List<String> getTableList(Statement statement) {
        aliasMappings = new LinkedHashMap<>();
        return super.getTableList(statement);
    }

    @Override
    public List<String> getTableList(Expression expr) {
        aliasMappings = new LinkedHashMap<>();
        return super.getTableList(expr);
    }

    @Override
    public <S> Void visit(Table tableName, S context) {
        super.visit(tableName, context);
        if (tableName.getAlias() != null) {
            String aliasName = tableName.getAlias().getName();
            aliasMappings.put(aliasName.toLowerCase(), tableName.getFullyQualifiedName());
        } else {
            aliasMappings.put(tableName.getName().toLowerCase(), tableName.getFullyQualifiedName());
        }
        return null;
    }

    @Override
    public <S> Void visit(PlainSelect plainSelect, S context) {
        if (plainSelect.getSelectItems() != null) {
            inWhere = true;
            for (SelectItem<?> item : plainSelect.getSelectItems()) {
                item.accept(this, context);
            }
            inWhere = false;
        }

        if (plainSelect.getFromItem() != null) {
            plainSelect.getFromItem().accept(this);
        }

        if (plainSelect.getJoins() != null) {
            inWhere = true;
            for (Join join : plainSelect.getJoins()) {
                join.getRightItem().accept(this);
            }
            inWhere = false;
        }
        if (plainSelect.getWhere() != null) {
            try {
                // this is an ugly hack because I'm not supporting scopes correctly,
                // the where clause analyzer should be keeping track of the scope here
                inWhere = true;
                plainSelect.getWhere().accept(this);
            } finally {
                inWhere = false;
            }
        }

        if (plainSelect.getHaving() != null) {
            plainSelect.getHaving().accept(this);
        }

        if (plainSelect.getOracleHierarchical() != null) {
            plainSelect.getOracleHierarchical().accept(this);
        }
        return null;
    }

    @Override
    public <S> Void visit(ParenthesedSelect parenthesedSelect, S context) {
        if (!inWhere) {
            super.visit(parenthesedSelect, context);
        }
        return null;
    }

    @Override
    public <S> Void visit(Drop drop, S context) {
        visit(drop.getName(), context);
        return null;
    }

    @Override
    public <S> Void visit(Truncate truncate, S context) {
        visit(truncate.getTable(), context);
        return null;
    }

    @Override
    public <S> Void visit(CreateIndex createIndex, S context) {
        visit(createIndex.getTable(), context);
        return null;
    }

    @Override
    public <S> Void visit(CreateView createView, S context) {
        visit(createView.getView(), context);
        if (createView.getSelect() != null) {
            createView.getSelect().accept((net.sf.jsqlparser.statement.select.SelectVisitor<Void>) this, context);
        }
        return null;
    }

    @Override
    public <S> Void visit(Alter alter, S context) {
        visit(alter.getTable(), context);
        return null;
    }

    @Override
    public <S> Void visit(Statements stmts, S context) {
        if (stmts.getStatements() != null) {
            for (Statement statement : stmts.getStatements()) {
                statement.accept(this);
            }
        }
        return null;
    }

    @Override
    public <S> Void visit(Execute execute, S context) {
        // still unsupported
        super.visit(execute, context);
        return null;
    }

    @Override
    public <S> Void visit(SetStatement set, S context) {
        set.accept(this);
        return null;
    }

    @Override
    public <S> Void visit(AlterView alterView, S context) {
        if (alterView.getSelect() != null) {
            alterView.getSelect().accept((net.sf.jsqlparser.statement.select.SelectVisitor<Void>) this, context);
        }
        visit(alterView.getView(), context);
        return null;
    }
}
