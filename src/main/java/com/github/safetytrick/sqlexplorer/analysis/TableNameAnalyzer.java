package com.github.safetytrick.sqlexplorer.analysis;

import java.util.stream.Collectors;

import com.github.safetytrick.sqlexplorer.core.AnalyzedSql;
import com.github.safetytrick.sqlexplorer.core.TableReference;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.util.TablesNamesFinder;

@Order(1)
@Component
public class TableNameAnalyzer implements SqlAnalyzer {

    @Override
    public void analyze(Statement statement, AnalyzedSql.Builder builder) {
        TablesNamesFinder finder = new TablesNamesFinder();

        builder.tables(finder.getTableList(statement).stream().map(TableReference::fromString)
                           .collect(Collectors.toList()));
    }
}
