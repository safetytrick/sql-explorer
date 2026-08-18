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

}
