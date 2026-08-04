package com.github.safetytrick.sqlexplorer.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.github.safetytrick.sqlexplorer.avro.TableReference;

/**
 * used to extract tables when parsing the whole query fails
 */
public class RegexTableExtractor {

    /**
     * The identifier after INSERT INTO / UPDATE / FROM / JOIN. The bounded
     * lookbehinds keep the UPDATE branch from firing on the keyword's other
     * uses: {@code ON DUPLICATE KEY UPDATE col = …} (which would capture the
     * first assigned column as a table) and {@code … FOR UPDATE SKIP LOCKED}
     * (which would capture the lock modifier).
     */
    private static final Pattern FROM_TABLE = Pattern.compile(
        "\\s(INSERT\\s+INTO|(?<!KEY\\s{1,20})(?<!FOR\\s{1,20})UPDATE|FROM|JOIN)\\s+"
            + "(`?(?<schema>[a-zA-Z_]\\w*)`?\\s*\\.\\s*)?`?(?<table>[a-zA-Z_]\\w*)`?\\s+",
        Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    public List<TableReference> extractTables(String schema, String query) {
        Matcher matcher = FROM_TABLE.matcher(query);
        List<TableReference> references = new ArrayList<>();
        while (matcher.find()) {
            String referencedSchema = matcher.group("schema");
            String table = matcher.group("table");
            references.add(TableReference.newTableReference(referencedSchema, table)
                .withDefaultSchema(schema));

        }
        return references;
    }

}
