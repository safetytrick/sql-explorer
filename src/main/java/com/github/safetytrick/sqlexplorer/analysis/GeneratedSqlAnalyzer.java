package com.github.safetytrick.sqlexplorer.analysis;

import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Order(4)
@Component
class GeneratedSqlAnalyzer implements SqlAnalyzer {
    @Override
    public void analyze(Statement statement, SqlProfile.Builder builder) {
        GeneratedSqlStatementVisitor visitor = new GeneratedSqlStatementVisitor();
        visitor.getTableList(statement);

        if (visitor.isHibernate) {
            builder.setGeneratedSource("hibernate");
        }
    }

    private static class GeneratedSqlStatementVisitor extends TablesNamesFinder<Void> {
        boolean isHibernate;

        @Override
        public <S> Void visit(Table tableName, S context) {
            super.visit(tableName, context);
            String aliasName = tableName.getAlias() == null ? null : tableName.getAlias().getName();
            isHibernate |= "`this_`".equals(aliasName);
            return null;
        }
    }
}
