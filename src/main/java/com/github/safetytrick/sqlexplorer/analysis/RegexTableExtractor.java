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

    /**
     * A schema-qualified table name, closed by a zero-width lookahead so the
     * character after it stays available to the next match. Wider than the
     * {@link #FROM_TABLE} tail because DDL crowds its names: {@code
     * REFERENCES `t`(`id`)} has no space, and a name can end a statement.
     */
    private static final String TABLE_REF =
        "(`?(?<schema>[a-zA-Z_]\\w*)`?\\s*\\.\\s*)?(?<quote>`?)(?<table>[a-zA-Z_]\\w*)`?(?=\\s|$|\\(|,|;)";

    /**
     * Table positions in DDL, which the parser never sees — everything in
     * {@code ignoredFirstTokens} reaches only the regex.
     *
     * <p>One pattern per position rather than one alternation, because the
     * discriminating context differs per statement and the combined form was
     * unreadable. Ingest runs these once per skipped row; the extra scans do
     * not register next to parsing.
     *
     * <p>What is deliberately absent matters as much as what is here. Every
     * one of these requires the object-type keyword — {@code DROP FUNCTION},
     * {@code DROP PROCEDURE}, {@code CREATE SCHEMA}, {@code ALTER SYSTEM_USER}
     * and {@code SHOW BINARY LOGS} all sit in the same statements and name
     * things that are not tables, and matching an identifier after a bare verb
     * is exactly how {@code ON UPDATE NOW} became a table called {@code now}.
     *
     * <p>Three positions are left out on purpose:
     * <ul>
     *   <li>{@code DROP TABLE} — a dropped table is absent from the schema by
     *       definition, so extracting it would fail every such profile against
     *       the check in {@code SqlEvidenceInputService}. One profile in the
     *       corpus; not worth guaranteed noise.
     *   <li>{@code CREATE TEMPORARY TABLE} — excluded for free, since the
     *       keyword sits between CREATE and TABLE. Hibernate's {@code ht_*}
     *       bulk-operation tables are transient and never in the schema.
     *   <li>{@code RENAME TABLE a TO b} — the old name is gone by the time
     *       anything looks, so half of every match would be noise. Zero
     *       occurrences in the corpus, and an untested branch for no yield is
     *       how the last extractor bug got in.
     * </ul>
     */
    private static final List<Pattern> DDL_TABLE_POSITIONS = List.of(
        // SHOW CREATE TABLE `t` — much the most common, 301 profiles
        ddl("\\bSHOW\\s+CREATE\\s+TABLE\\s+"),
        // CREATE TABLE [IF NOT EXISTS] `t`; TEMPORARY does not match, by
        // construction. The SHOW lookbehind keeps this off `SHOW CREATE TABLE`,
        // which the position above already claims — otherwise every one of
        // those 301 profiles listed its table twice.
        ddl("\\b(?<!\\bSHOW\\s{1,20})CREATE\\s+TABLE\\s+(IF\\s+NOT\\s+EXISTS\\s+)?"),
        ddl("\\bALTER\\s+TABLE\\s+"),
        ddl("\\bTRUNCATE\\s+(TABLE\\s+)?"),
        // the parent side of a foreign key, inside a CREATE or ALTER body
        ddl("\\bREFERENCES\\s+"),
        // CREATE [UNIQUE] INDEX `name` ON `t` (`col`)
        ddl("\\bCREATE\\s+(UNIQUE\\s+)?INDEX\\s+`?\\w+`?\\s+ON\\s+"),
        // CREATE TRIGGER `name` BEFORE UPDATE ON `t` — the ON that produced
        // `mysql.on` before the keyword guard; here it names the real table
        ddl("\\bTRIGGER\\s+`?\\w+`?\\s+(BEFORE|AFTER)\\s+\\w+\\s+ON\\s+"),
        // GRANT/REVOKE … ON `s`.`t` TO|FROM …, where the trailing TO/FROM is
        // what separates a privilege object from a JOIN condition. The
        // schema-wide `s`.* form cannot match: * is not an identifier.
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
     * Names from the *guessed* positions that this schema does not know.
     *
     * <p>Scoped to {@link #FROM_TABLE} on purpose. That pattern infers a table
     * from position alone — the identifier after FROM/JOIN/UPDATE/INSERT INTO
     * — so it can match text that is not a table reference at all, which is
     * how {@code ON UPDATE NOW} became a table called {@code now}. Nothing
     * corroborates it, so the schema does.
     *
     * <p>The DDL positions are not checked, because there is nothing left to
     * corroborate: each one requires its object-type keyword, so {@code ALTER
     * TABLE `x`} names a table by construction. Checking them anyway would
     * report far more than it caught — 162 profiles in the corpus run
     * {@code SHOW CREATE TABLE} against view-swap artifacts
     * ({@code stat_get_user_list_<hex>}) that were real when the statement ran
     * and have since been dropped. Those are true references to absent tables,
     * which is a fact about the schema rather than a bug in this extractor.
     *
     * <p>Returns empty when there is no schema to check against, which is the
     * honest answer rather than "everything is unknown" — analysis runs
     * without a schema in tests and on the no-schema convenience path.
     */
    public List<TableReference> unknownTables(String defaultSchema, String query, SqlSchemaRepository schema) {
        if (schema == null || query == null) {
            return List.of();
        }
        List<TableReference> guessed = new ArrayList<>();
        collect(FROM_TABLE, defaultSchema, query, guessed);

        Set<TableReference> unknown = new LinkedHashSet<>();
        for (TableReference table : guessed) {
            if (schema.getTable(table) == null) {
                unknown.add(table);
            }
        }
        return List.copyOf(unknown);
    }
}
