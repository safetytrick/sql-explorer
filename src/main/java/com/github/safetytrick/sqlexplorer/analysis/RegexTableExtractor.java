package com.github.safetytrick.sqlexplorer.analysis;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.schema.SqlSchemaRepository;

public class RegexTableExtractor {

    private static final Pattern FROM_TABLE = Pattern.compile(
        "\\s(INSERT\\s+INTO|(?<!KEY\\s{1,20})(?<!FOR\\s{1,20})(?<!\\bON\\s{1,20})UPDATE|FROM|JOIN)\\s+"
            + "(`?(?<schema>[a-zA-Z_]\\w*)`?\\s*\\.\\s*)?(?<quote>`?)(?<table>[a-zA-Z_]\\w*)`?(?=\\s|$)",
        Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    private static final String TABLE_REF =
        "(`?(?<schema>[a-zA-Z_]\\w*)`?\\s*\\.\\s*)?(?<quote>`?)(?<table>[a-zA-Z_]\\w*)`?(?=\\s|$|\\(|,|;)";

    private static final List<Pattern> DDL_TABLE_POSITIONS = List.of(
        ddl("\\bSHOW\\s+CREATE\\s+TABLE\\s+"),
        ddl("\\b(?<!\\bSHOW\\s{1,20})CREATE\\s+TABLE\\s+(IF\\s+NOT\\s+EXISTS\\s+)?"),
        ddl("\\bALTER\\s+TABLE\\s+"),
        ddl("\\bTRUNCATE\\s+(TABLE\\s+)?"),
        ddl("\\bREFERENCES\\s+"),
        ddl("\\bCREATE\\s+(UNIQUE\\s+)?INDEX\\s+`?\\w+`?\\s+ON\\s+"),
        ddl("\\bTRIGGER\\s+`?\\w+`?\\s+(BEFORE|AFTER)\\s+\\w+\\s+ON\\s+"),
        ddl("\\bON\\s+", "\\s+(TO|FROM)\\b"));

    private static Pattern ddl(String prefix) {
        return ddl(prefix, "");
    }

    private static Pattern ddl(String prefix, String suffix) {
        return Pattern.compile(prefix + TABLE_REF + suffix,
                               Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);
    }

    public List<TableReference> extractTables(String schema, String query) {
        List<TableReference> references = new ArrayList<>();
        collect(FROM_TABLE, schema, query, references);
        for (Pattern ddlPosition : DDL_TABLE_POSITIONS) {
            collect(ddlPosition, schema, query, references);
        }
        return references;
    }

    private void collect(Pattern pattern, String schema, String query, List<TableReference> references) {
        Matcher matcher = pattern.matcher(query);
        while (matcher.find()) {
            String referencedSchema = matcher.group("schema");
            String table = matcher.group("table");

            if (matcher.group("quote").isEmpty() && "ON".equalsIgnoreCase(table)) {
                continue;
            }

            references.add(TableReference.newTableReference(referencedSchema, table)
                .withDefaultSchema(schema));

        }
    }

    public static final String UNKNOWN_TABLE_REASON = "Extracted table not in schema:";

    public List<TableReference> unknownTables(String defaultSchema, String query, SqlSchemaRepository schema) {
        if (schema == null || query == null) {
            return List.of();
        }
        List<TableReference> guessed = new ArrayList<>();
        collect(FROM_TABLE, defaultSchema, query, guessed);

        Set<TableReference> unknown = new LinkedHashSet<>();
        for (TableReference table : guessed) {
            if (table.getSchemaName() == null) {
                continue;
            }
            if (schema.getTable(table) == null) {
                unknown.add(table);
            }
        }
        return List.copyOf(unknown);
    }
}
