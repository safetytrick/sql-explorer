package com.github.safetytrick.sqlexplorer.analysis;

import java.util.stream.Collectors;

import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.schema.SqlSchemaRepository;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Order(1)
@Component
class TableNameAnalyzer implements SqlAnalyzer {

    @Override
    public void analyze(Statement statement, SqlProfile.Builder builder, SqlSchemaRepository schema) {
        TablesNamesFinder<Void> finder = new TablesNamesFinder<>();

        builder.setTables(finder.getTables(statement).stream().map(TableReference::fromString)
                           .collect(Collectors.toList()));
    }
}
