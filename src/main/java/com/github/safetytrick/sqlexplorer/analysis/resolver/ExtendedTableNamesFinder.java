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
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SubSelect;
import net.sf.jsqlparser.statement.truncate.Truncate;
import net.sf.jsqlparser.util.TablesNamesFinder;

class ExtendedTableNamesFinder extends TablesNamesFinder {
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
    public void visit(Table tableName) {
        super.visit(tableName);
        if (tableName.getAlias() != null) {
            String aliasName = tableName.getAlias().getName();
            aliasMappings.put(aliasName.toLowerCase(), tableName.getFullyQualifiedName());
        } else {
            aliasMappings.put(tableName.getName().toLowerCase(), tableName.getFullyQualifiedName());
        }
    }

    @Override
    public void visit(PlainSelect plainSelect) {
        if (plainSelect.getSelectItems() != null) {
            inWhere = true;
            for (SelectItem item : plainSelect.getSelectItems()) {
                item.accept(this);
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
    }

    @Override
    public void visit(SubSelect subSelect) {
        if (!inWhere) {
            super.visit(subSelect);
        }
    }

    @Override
    public void visit(Drop drop) {
        visit(drop.getName());
    }

    @Override
    public void visit(Truncate truncate) {
        visit(truncate.getTable());
    }

    @Override
    public void visit(CreateIndex createIndex) {
        visit(createIndex.getTable());
    }

    @Override
    public void visit(CreateView createView) {
        visit(createView.getView());
        if (createView.getSelect() != null) {
            createView.getSelect().accept(this);
        }
    }

    @Override
    public void visit(Alter alter) {
        visit(alter.getTable());
    }

    @Override
    public void visit(Statements stmts) {
        if (stmts.getStatements() != null) {
            for (Statement statement : stmts.getStatements()) {
                statement.accept(this);
            }
        }
    }

    @Override
    public void visit(Execute execute) {
        // still unsupported
        super.visit(execute);
    }

    @Override
    public void visit(SetStatement set) {
        set.accept(this);
    }

    @Override
    public void visit(AlterView alterView) {
        if (alterView.getSelectBody() != null) {
            alterView.getSelectBody().accept(this);
        }
        visit(alterView.getView());
    }
}
