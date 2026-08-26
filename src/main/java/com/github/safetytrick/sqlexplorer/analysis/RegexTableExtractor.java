package com.github.safetytrick.sqlexplorer.analysis;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.schema.SqlSchemaRepository;

/**
 * used to extract tables when parsing the whole query fails
 */
public class RegexTableExtractor {

    /**
     * The identifier after INSERT INTO / UPDATE / FROM / JOIN. The bounded
     * lookbehinds keep the UPDATE branch from firing on the keyword's other
     * uses: {@code ON DUPLICATE KEY UPDATE col = …} (which would capture the
     * first assigned column as a table), {@code … FOR UPDATE SKIP LOCKED}
     * (which would capture the lock modifier), and {@code ON UPDATE …} — the
     * DDL clause, which captured its referential action, putting tables named
     * `now`, `cascade`, `restrict` and `no` (from NO ACTION) into the schema
     * tree. That last one is word-boundary anchored so a table whose name
     * merely ends in "on" does not suppress a real UPDATE statement.
     *
     * <p>The remaining case runs the other way: in {@code GRANT … UPDATE ON
     * `s`.`t`} and {@code CREATE TRIGGER … BEFORE UPDATE ON `t`}, UPDATE is a
     * privilege or a trigger event and the word after it is {@code ON}, which
     * no lookbehind can reach. That one is rejected after capture — see
     * {@link #extractTables}.
     *
     * <p>The table name is closed by a zero-width lookahead rather than by
     * consuming the whitespace after it. Consuming it cost two kinds of hit:
     * a table at the very end of the query never matched at all (nothing
     * follows it to consume), and in {@code FROM a JOIN b} the space eaten
     * after {@code a} was the same space the next match needed for its
     * leading {@code \s}, so every table after the first was dropped unless
     * an alias happened to sit in between.
     */
    private static final Pattern FROM_TABLE = Pattern.compile(
        "\\s(INSERT\\s+INTO|(?<!KEY\\s{1,20})(?<!FOR\\s{1,20})(?<!\\bON\\s{1,20})UPDATE|FROM|JOIN)\\s+"
            + "(`?(?<schema>[a-zA-Z_]\\w*)`?\\s*\\.\\s*)?(?<quote>`?)(?<table>[a-zA-Z_]\\w*)`?(?=\\s|$)",
        Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    public List<TableReference> extractTables(String schema, String query) {
        Matcher matcher = FROM_TABLE.matcher(query);
        List<TableReference> references = new ArrayList<>();
        while (matcher.find()) {
            String referencedSchema = matcher.group("schema");
            String table = matcher.group("table");

            // `GRANT … UPDATE ON `s`.`t`` and `… BEFORE UPDATE ON `t`` put the
            // keyword ON where a table name goes, and the optional schema group
            // cannot absorb it (no dot follows), so it was emitted as a table —
            // `mysql.on` in the live corpus. Only the bare word is rejected: a
            // table really named `on` has to be quoted, since ON is reserved.
            if (matcher.group("quote").isEmpty() && "ON".equalsIgnoreCase(table)) {
                continue;
            }

            references.add(TableReference.newTableReference(referencedSchema, table)
                .withDefaultSchema(schema));

        }
        return references;
    }

    /**
     * The reason recorded when an extracted name is not a table in the
     * environment. Prefix is stable so tests and reports can find these.
     *
     * <p>Not the {@code "Failure on:"} prefix that marks an analyzer
     * exception, so these classify as parse-kind failures in
     * {@code QuerySearchService.matchesFailureKind} and land in the
     * parse-failure report. That is the closest existing bucket and matches
     * what the finding is — the pre-scan read the statement wrongly — but it
     * is a bucket, not a category: a third {@code FailureKind} would name it
     * properly if these ever become common enough to want separating.
     */
    public static final String UNKNOWN_TABLE_REASON = "Extracted table not in schema:";

    /**
     * The extracted names this schema does not know, in encounter order.
     *
     * <p>The regex is a guess — it reads statements the parser never sees, so
     * nothing else corroborates its output. When the environment's schema is
     * loaded it can: a name that is not a table there means the pattern
     * matched something that is not a table reference. That is how
     * {@code ON UPDATE NOW} became a table called {@code now}, and it went
     * unnoticed until the names showed up in the schema tree.
     *
     * <p>Returns empty when there is no schema to check against, which is the
     * honest answer rather than "everything is unknown" — analysis runs
     * without a schema in tests and on the no-schema convenience path.
     */
    public List<TableReference> unknownTables(List<TableReference> extracted, SqlSchemaRepository schema) {
        if (schema == null || extracted == null) {
            return List.of();
        }
        Set<TableReference> unknown = new LinkedHashSet<>();
        for (TableReference table : extracted) {
            if (schema.getTable(table) == null) {
                unknown.add(table);
            }
        }
        return List.copyOf(unknown);
    }
}
