package com.github.safetytrick.sqlexplorer.analysis;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.github.safetytrick.sqlexplorer.avro.TableReference;
import org.springframework.stereotype.Component;

@Component
public class IgnoredSchemas {

    private final Set<String> ignoredSchemas;
    {
        var ignoredSchemas = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        ignoredSchemas.addAll(Arrays.asList(
            "sys", "performance_schema", "mysql", "information_schema", "percona"
        ));
        this.ignoredSchemas = ignoredSchemas;
    }

    public boolean contains(String schema) {
        return schema != null && ignoredSchemas.contains(schema);
    }

    public boolean ignoreAll(List<TableReference> tables) {
        return !tables.isEmpty()
            && tables.stream().map(TableReference::getSchemaName).allMatch(this::contains);
    }

}
