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

    /**
     * True when every table named here lives in an ignored schema.
     *
     * <p>An empty list is <em>not</em> ignorable. {@code allMatch} is
     * vacuously true on an empty stream, and the caller uses this to decide
     * whether to skip analysis — so "the regex pre-scan found no tables" was
     * being read as "all of its tables are ignorable", and the query was
     * dropped before the real parser ever saw it. A query we could not read
     * cheaply is exactly the one worth parsing properly.
     */
    public boolean ignoreAll(List<TableReference> tables) {
        return !tables.isEmpty()
            && tables.stream().map(TableReference::getSchemaName).allMatch(this::contains);
    }

}
