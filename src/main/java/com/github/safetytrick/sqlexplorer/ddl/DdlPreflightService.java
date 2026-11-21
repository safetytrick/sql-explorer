package com.github.safetytrick.sqlexplorer.ddl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.ddl.AlterParser.Clause;
import com.github.safetytrick.sqlexplorer.ddl.AlterParser.ColumnDef;
import com.github.safetytrick.sqlexplorer.ddl.AlterParser.IndexDef;
import com.github.safetytrick.sqlexplorer.ddl.AlterParser.IndexPart;
import com.github.safetytrick.sqlexplorer.ddl.AlterParser.Kind;
import com.github.safetytrick.sqlexplorer.ddl.AlterParser.ParsedAlter;
import com.github.safetytrick.sqlexplorer.avro.ColumnReference;
import com.github.safetytrick.sqlexplorer.profiles.TableWorkloadService;
import com.github.safetytrick.sqlexplorer.profiles.TableWorkloadService.LockRisk;
import com.github.safetytrick.sqlexplorer.profiles.TableWorkloadService.WriteRate;
import com.github.safetytrick.sqlexplorer.schema.ForeignKey;
import com.github.safetytrick.sqlexplorer.schema.SqlSchemaService;
import com.github.safetytrick.sqlexplorer.schema.TableColumn;
import com.github.safetytrick.sqlexplorer.schema.TableIndex;
import com.github.safetytrick.sqlexplorer.schema.TableStats;
import com.github.safetytrick.sqlexplorer.schema.TableStructure;
import org.springframework.stereotype.Service;

@Service
public class DdlPreflightService {

    static final int MAX_ROW_BYTES = 65_535;
    static final int MAX_KEY_BYTES = 3_072;
    static final int MAX_KEY_PART_BYTES_COMPACT = 767;
    static final long LARGE_TABLE_BYTES = 1L << 30;
    static final long LARGE_TABLE_ROWS = 10_000_000L;

    private final SqlSchemaService schemaService;
    private final TableWorkloadService workloadService;

    public DdlPreflightService(SqlSchemaService schemaService, TableWorkloadService workloadService) {
        this.schemaService = schemaService;
        this.workloadService = workloadService;
    }

    public enum Algorithm { INSTANT, INPLACE, INPLACE_REBUILD, COPY, UNKNOWN }

    public enum Severity { OK, INFO, WARN, BLOCK }

    public record Check(Severity severity, String title, String detail) { }

    public record ClauseVerdict(String text, String kind, Algorithm algorithm, boolean rebuildsTable,
                                boolean concurrentDml, String reason) { }

    public record TableFacts(boolean known, Long rowsEstimate, Long sizeBytes, String rowFormat,
                             String declaredCollation, int servers, boolean childOfForeignKey,
                             List<String> referencedBy) { }

    public record Workload(double writesPerDay, double rowsChangedPerDay, double longThresholdMs,
                           int longStatements, double longExecutionsPerDay, double maxTimeMs) { }

    public record Preflight(String statement, String error, String schema, String table, List<ClauseVerdict> clauses,
                            Algorithm algorithm, boolean rebuildsTable, boolean concurrentDml,
                            String requestedAlgorithm, String requestedLock, TableFacts tableFacts,
                            Workload workload, List<Check> checks, List<String> recommendations,
                            List<String> unknowns, List<AlgorithmChoice> choices, List<LockedTable> lockedTables) { }

    public enum Role { PARENT, CHILD }

    public enum Reach { EVERY_QUERY, WRITES }

    public record LockedTable(String schema, String table, Role role, Reach reach, String waits,
                              List<String> constraints, String reason, Workload workload) { }

    public enum Outcome { CHOSEN, NEEDED, SLOWER, REFUSED, UNPREDICTED }

    public record AlgorithmChoice(String algorithm, Outcome outcome, boolean requested, String implication) { }

    public Preflight check(String sql, double longStatementMs) {
        ParsedAlter parsed = AlterParser.parse(sql);
        if (parsed.error() != null && parsed.clauses().isEmpty()) {
            return new Preflight(sql, parsed.error(), null, null, List.of(), Algorithm.UNKNOWN, false, false, null,
                null, null, null, List.of(), List.of(), List.of(), List.of(), List.of());
        }
        TableReference reference = TableReference.newTableReference(parsed.schema(), parsed.table());
        TableStructure structure = schemaService.getTable(reference);
        Map<String, ColumnInfo> columns = columnsOf(structure);

        List<ClauseVerdict> verdicts = new ArrayList<>();
        List<Check> checks = new ArrayList<>();
        Set<String> unknowns = new LinkedHashSet<>();
        boolean hasInstantColumnChange = false;
        for (Clause clause : parsed.clauses()) {
            if (clause.kind() == Kind.ALGORITHM || clause.kind() == Kind.LOCK) {
                continue;
            }
            ClauseVerdict verdict = verdict(clause, columns, structure, unknowns);
            verdicts.add(verdict);
            if (verdict.algorithm() == Algorithm.INSTANT
                && (clause.kind() == Kind.ADD_COLUMN || clause.kind() == Kind.DROP_COLUMN)) {
                hasInstantColumnChange = true;
            }
        }

        Algorithm overall = verdicts.stream().map(ClauseVerdict::algorithm)
            .max(Enum::compareTo).orElse(Algorithm.UNKNOWN);
        boolean rebuilds = verdicts.stream().anyMatch(ClauseVerdict::rebuildsTable);
        boolean concurrent = verdicts.stream().allMatch(ClauseVerdict::concurrentDml);
        String requestedAlgorithm = parsed.requested(Kind.ALGORITHM);
        String requestedLock = parsed.requested(Kind.LOCK);

        TableFacts facts = facts(structure, reference);
        Workload workload = workload(reference, longStatementMs, structure != null);

        for (String problem : parsed.syntaxErrors()) {
            checks.add(new Check(Severity.BLOCK, "MySQL rejects this syntax", problem
                + " The rest of this check reads the statement as corrected."));
        }
        if (structure == null) {
            checks.add(new Check(Severity.WARN, "Table not in the schema snapshot",
                "No such table in this environment's snapshot, so columns, indexes, sizes and foreign keys are "
                    + "unknown and the algorithm is a guess from the statement alone."));
        }
        mixedAlgorithms(verdicts, overall, checks);
        requested(requestedAlgorithm, requestedLock, overall, concurrent, checks);
        if (structure != null) {
            limits(parsed, columns, structure, facts, checks, unknowns);
            collations(parsed, columns, structure, checks);
        }
        if (hasInstantColumnChange) {
            checks.add(new Check(Severity.INFO, "Uses an instant row version",
                "Each instant ADD or DROP COLUMN uses one of InnoDB's 64 row versions per table; at 64 the next "
                    + "one must rebuild. The current count (INFORMATION_SCHEMA.INNODB_TABLES.TOTAL_ROW_VERSIONS) "
                    + "is not captured."));
        }
        foreignKeys(facts, overall, rebuilds, checks);
        List<LockedTable> locked = structure == null ? List.of()
            : lockedTables(parsed, structure, longStatementMs);
        lockedTablesCheck(locked, checks);

        List<String> recommendations = recommendations(overall, rebuilds, concurrent, requestedAlgorithm, facts,
            workload, locked);
        unknowns.add("Triggers on the table are not captured; pt-online-schema-change and gh-ost refuse tables "
            + "that already have them.");
        return new Preflight(sql, parsed.error(), parsed.schema(), parsed.table(), verdicts, overall, rebuilds,
            concurrent, requestedAlgorithm, requestedLock, facts, workload, checks, recommendations,
            List.copyOf(unknowns), choices(overall, hasInstantColumnChange, concurrent, requestedAlgorithm, facts),
            locked);
    }

    /**
     * What stating each algorithm would do here. MySQL never falls back to a cheaper algorithm than the one
     * stated, and refuses one cheaper than the change needs; with none stated it picks the cheapest the
     * change supports.
     */
    List<AlgorithmChoice> choices(Algorithm needed, boolean instantColumnChange, boolean concurrent, String requested,
                                  TableFacts facts) {
        String asked = requested == null ? "DEFAULT" : requested.toUpperCase(Locale.ROOT);
        List<AlgorithmChoice> out = new ArrayList<>();
        out.add(new AlgorithmChoice("DEFAULT", Outcome.CHOSEN, "DEFAULT".equals(asked),
            needed == Algorithm.UNKNOWN
                ? "MySQL picks the cheapest algorithm the change supports; this check cannot predict which."
                : "MySQL picks the cheapest algorithm the change supports: " + label(needed) + " here. The same "
                    + "as stating it, except that if this prediction is wrong MySQL quietly uses something "
                    + "slower instead of refusing."));
        for (String choice : List.of("INSTANT", "INPLACE", "COPY")) {
            boolean isAsked = choice.equals(asked);
            if (needed == Algorithm.UNKNOWN) {
                out.add(new AlgorithmChoice(choice, Outcome.UNPREDICTED, isAsked, unpredicted(choice)));
                continue;
            }
            int want = rank(sqlAlgorithm(needed));
            int have = rank(choice);
            if (have < want) {
                out.add(new AlgorithmChoice(choice, Outcome.REFUSED, isAsked, "Refused: the change needs "
                    + label(needed) + ". MySQL returns an error and nothing runs, so stating it is a safe guard "
                    + "that here would stop the statement."));
            } else if (have == want) {
                out.add(new AlgorithmChoice(choice, Outcome.NEEDED, isAsked,
                    needs(needed, concurrent, facts) + " Stating it makes MySQL refuse rather than do anything "
                        + "slower."));
            } else {
                out.add(new AlgorithmChoice(choice, Outcome.SLOWER, isAsked, slower(choice, needed, instantColumnChange, facts)));
            }
        }
        return out;
    }

    private static String needs(Algorithm needed, boolean concurrent, TableFacts facts) {
        return switch (needed) {
            case INSTANT -> "Metadata only: done in moments whatever the table's size. It still waits for the "
                + "table's metadata lock, so a long-running query on the table holds it up, and every later query "
                + "queues behind it.";
            case INPLACE -> "Works in place without copying rows; "
                + (concurrent ? "writes continue" : "writes are blocked while it runs (LOCK=SHARED)")
                + ". Replicas apply it only once the primary has finished, and lag for as long as it took.";
            case INPLACE_REBUILD -> "Rewrites the table in place" + size(facts) + "; "
                + (concurrent ? "writes continue, apart from brief exclusive locks at the start and end"
                : "writes are blocked while it runs (LOCK=SHARED)")
                + ". Needs free disk of about the table's size, plus room to log the writes made meanwhile; "
                + "replicas lag for the whole rebuild.";
            case COPY -> "Copies every row into a new table" + size(facts) + " and blocks writes until it "
                + "finishes; reads continue. Needs free disk for a full copy, and replicas then lag for as long "
                + "again.";
            case UNKNOWN -> "";
        };
    }

    private static String slower(String choice, Algorithm needed, boolean instantColumnChange, TableFacts facts) {
        if ("COPY".equals(choice)) {
            return "Accepted, but forces a full copy" + size(facts) + " that blocks writes until it finishes, "
                + "where " + label(needed) + " would do. There is rarely a reason to.";
        }
        return instantColumnChange
            ? "Accepted, but an INPLACE ADD or DROP COLUMN rebuilds the table" + size(facts) + " where INSTANT only "
                + "changes metadata. Worth it only to keep one of the table's 64 instant row versions."
            : "Accepted, and done in place without copying rows, but INSTANT would not need even that.";
    }

    private static String unpredicted(String choice) {
        return switch (choice) {
            case "INSTANT" -> "If MySQL accepts it, a metadata-only change; otherwise it refuses and nothing runs.";
            case "INPLACE" -> "If MySQL accepts it, no row copy and writes usually continue; it may still rebuild "
                + "the table. Otherwise it refuses and nothing runs.";
            default -> "Always accepted: copies the whole table and blocks writes until it finishes.";
        };
    }

    private ClauseVerdict verdict(Clause clause, Map<String, ColumnInfo> columns, TableStructure structure,
                                  Set<String> unknowns) {
        return switch (clause.kind()) {
            case ADD_COLUMN -> {
                ColumnDef column = clause.column();
                if (column.generated() && column.stored()) {
                    yield v(clause, Algorithm.COPY, true, false, "Adding a STORED generated column copies the table.");
                }
                unknowns.add("Whether the table has a FULLTEXT index: the snapshot does not distinguish index "
                    + "types, and a FULLTEXT index rules out ALGORITHM=INSTANT for ADD and DROP COLUMN.");
                yield v(clause, Algorithm.INSTANT, false, true,
                    "ADD COLUMN is instant in any position (8.0.29+), unless the table is ROW_FORMAT=COMPRESSED, "
                        + "has a FULLTEXT index, or has used its 64 row versions.");
            }
            case DROP_COLUMN -> {
                if (indexed(structure, clause.name())) {
                    yield v(clause, Algorithm.INPLACE_REBUILD, true, true,
                        "The column is part of an index, which must be rebuilt without it.");
                }
                yield v(clause, Algorithm.INSTANT, false, true,
                    "DROP COLUMN is instant (8.0.29+) when no index uses the column, with the same exceptions as "
                        + "ADD COLUMN.");
            }
            case RENAME_COLUMN -> v(clause, Algorithm.INSTANT, false, true,
                "RENAME COLUMN changes metadata only (8.0.28+).");
            case COLUMN_DEFAULT, COLUMN_VISIBILITY -> v(clause, Algorithm.INSTANT, false, true,
                "Changing a column's default or visibility is metadata only.");
            case MODIFY_COLUMN, CHANGE_COLUMN -> changeColumn(clause, columns);
            case ADD_INDEX -> {
                IndexDef index = clause.index();
                if (index.fulltext()) {
                    yield v(clause, Algorithm.INPLACE_REBUILD, true, false,
                        "A FULLTEXT index builds in place but blocks writes (LOCK=SHARED); the table's first "
                            + "FULLTEXT index also rebuilds it to add FTS_DOC_ID.");
                }
                if (index.spatial()) {
                    yield v(clause, Algorithm.INPLACE, false, false,
                        "A SPATIAL index builds in place but blocks writes (LOCK=SHARED).");
                }
                yield v(clause, Algorithm.INPLACE, false, true,
                    "A secondary index builds in place with writes allowed, but reads the whole table, and "
                        + "replicas apply it only after the primary finishes.");
            }
            case DROP_INDEX, RENAME_INDEX, INDEX_VISIBILITY, DROP_FOREIGN_KEY, DROP_CONSTRAINT, AUTO_INCREMENT,
                 COMMENT, TABLE_DEFAULT_CHARSET -> v(clause, Algorithm.INPLACE, false, true,
                "Metadata only: nothing is rewritten.");
            case RENAME_TABLE -> v(clause, Algorithm.INSTANT, false, true, "Renaming a table is metadata only.");
            case ADD_PRIMARY_KEY -> v(clause, Algorithm.INPLACE_REBUILD, true, true,
                "Adding a primary key rebuilds the table, the clustered index, in place.");
            case DROP_PRIMARY_KEY -> v(clause, Algorithm.COPY, true, false,
                "Dropping the primary key without adding another copies the table and blocks writes.");
            case ADD_FOREIGN_KEY -> v(clause, Algorithm.INPLACE, false, true,
                "In place only with SET foreign_key_checks = 0; with checks on, MySQL copies the table.");
            case CONVERT_CHARSET -> v(clause, Algorithm.COPY, true, false,
                "CONVERT TO CHARACTER SET re-encodes every character column: a table copy that blocks writes.");
            case REBUILD, ROW_FORMAT -> v(clause, Algorithm.INPLACE_REBUILD, true, true,
                "Rebuilds the table in place, with writes allowed.");
            default -> v(clause, Algorithm.UNKNOWN, false, false,
                "Not a clause this check recognises; verify its algorithm with an explicit ALGORITHM=.");
        };
    }

    private ClauseVerdict changeColumn(Clause clause, Map<String, ColumnInfo> columns) {
        ColumnInfo current = columns.get(lower(clause.name()));
        ColumnDef next = clause.column();
        if (current == null) {
            return v(clause, Algorithm.COPY, true, false,
                "The column is not in the snapshot, so this assumes a type change: a table copy.");
        }
        boolean renamed = clause.kind() == Kind.CHANGE_COLUMN && !current.name().equalsIgnoreCase(next.name());
        String nextCharset = next.charset() != null ? next.charset()
            : next.collation() != null ? charsetOf(next.collation()) : current.charset();
        boolean typeSame = baseType(current.type()).equals(baseType(next.type()));
        boolean charsetSame = Objects.equals(lower(current.charset()), lower(nextCharset));
        boolean collationSame = next.collation() == null || Objects.equals(lower(current.collation()),
            lower(next.collation()));
        boolean nullSame = next.nullable() == null || next.nullable() == current.nullable();
        boolean lengthSame = next.length() == null || Objects.equals(next.length(), current.length());

        if (typeSame && charsetSame && collationSame && nullSame && lengthSame) {
            return v(clause, Algorithm.INSTANT, false, true,
                renamed ? "Only the name changes: metadata only." : "Nothing about the stored data changes.");
        }
        if (!charsetSame || !collationSame) {
            return v(clause, Algorithm.COPY, true, false,
                "Changing a column's character set or collation re-encodes it: a table copy that blocks writes.");
        }
        if (typeSame && isVarchar(current.type()) && next.length() != null && current.length() != null
            && next.length() > current.length()) {
            int bytesPerChar = bytesPerChar(current.charset());
            boolean sameLengthPrefix = (current.length() * bytesPerChar <= 255) == (next.length() * bytesPerChar <= 255);
            if (sameLengthPrefix && nullSame) {
                return v(clause, Algorithm.INPLACE, false, true,
                    "Widening a VARCHAR within the same length-prefix size (both ≤ 255 bytes or both above) is "
                        + "metadata only.");
            }
            return v(clause, Algorithm.COPY, true, false,
                "Widening a VARCHAR across 255 bytes changes its length prefix from one byte to two: a table copy.");
        }
        if (typeSame && lengthSame && !nullSame) {
            return v(clause, Algorithm.INPLACE_REBUILD, true, true,
                "Changing NULL / NOT NULL rebuilds the table in place, with writes allowed.");
        }
        return v(clause, Algorithm.COPY, true, false,
            "Changing a column's data type or shortening it copies the table and blocks writes.");
    }

    private static ClauseVerdict v(Clause clause, Algorithm algorithm, boolean rebuilds, boolean concurrent,
                                   String reason) {
        return new ClauseVerdict(clause.text(), clause.kind().name(), algorithm, rebuilds, concurrent, reason);
    }

    private void mixedAlgorithms(List<ClauseVerdict> verdicts, Algorithm overall, List<Check> checks) {
        boolean anyCheaper = verdicts.stream().anyMatch(v -> v.algorithm().compareTo(overall) < 0
            && v.algorithm() != Algorithm.UNKNOWN);
        if (anyCheaper && verdicts.size() > 1) {
            checks.add(new Check(Severity.WARN, "Clauses with different costs in one statement",
                "MySQL runs the whole statement with the most expensive algorithm any clause needs ("
                    + label(overall) + "). Run cheaper changes as separate statements so they stay cheap."));
        }
    }

    private void requested(String algorithm, String lock, Algorithm overall, boolean concurrent, List<Check> checks) {
        if (algorithm == null) {
            checks.add(new Check(Severity.INFO, "No ALGORITHM= stated",
                "Add ALGORITHM=" + sqlAlgorithm(overall) + " so MySQL refuses the statement rather than quietly "
                    + "using a slower algorithm than expected."));
        } else if (rank(algorithm) >= 0 && overall != Algorithm.UNKNOWN && rank(algorithm) < rank(sqlAlgorithm(overall))) {
            checks.add(new Check(Severity.BLOCK, "MySQL will refuse this as written",
                "ALGORITHM=" + algorithm + " was requested, but the change needs " + label(overall)
                    + ". That refusal is safe: nothing runs. Change the statement or the algorithm."));
        }
        if ("NONE".equalsIgnoreCase(lock) && !concurrent) {
            checks.add(new Check(Severity.BLOCK, "LOCK=NONE is not possible",
                "A clause blocks writes, so MySQL will refuse LOCK=NONE."));
        }
    }

    private void limits(ParsedAlter parsed, Map<String, ColumnInfo> columns, TableStructure structure,
                        TableFacts facts, List<Check> checks, Set<String> unknowns) {
        Map<String, ColumnInfo> after = new LinkedHashMap<>(columns);
        List<IndexShape> indexes = new ArrayList<>();
        for (TableIndex index : structure.indices() == null ? List.<TableIndex>of() : structure.indices()) {
            if (indexes.stream().noneMatch(i -> i.name().equalsIgnoreCase(index.name()))) {
                indexes.add(new IndexShape(index.name(), index.unique(),
                    index.columns().stream().map(c -> new IndexPart(c, null, false)).toList(), false));
            }
        }
        for (Clause clause : parsed.clauses()) {
            switch (clause.kind()) {
                case ADD_COLUMN -> after.put(lower(clause.column().name()), ColumnInfo.of(clause.column()));
                case DROP_COLUMN -> after.remove(lower(clause.name()));
                case MODIFY_COLUMN -> after.put(lower(clause.name()), merge(after.get(lower(clause.name())), clause.column()));
                case CHANGE_COLUMN -> {
                    ColumnInfo old = after.remove(lower(clause.name()));
                    after.put(lower(clause.column().name()), merge(old, clause.column()));
                }
                case RENAME_COLUMN -> {
                    ColumnInfo old = after.remove(lower(clause.name()));
                    if (old != null) {
                        after.put(lower(clause.newName()), old.renamed(clause.newName()));
                    }
                }
                case CONVERT_CHARSET -> after.replaceAll((name, column) -> column.isString()
                    ? column.withCharset(clause.value(), clause.collation()) : column);
                case ADD_INDEX, ADD_PRIMARY_KEY -> indexes.add(new IndexShape(
                    clause.index().name() == null ? "(new index)" : clause.index().name(), clause.index().unique(),
                    clause.index().parts(), true));
                case DROP_INDEX -> indexes.removeIf(i -> i.name().equalsIgnoreCase(clause.name()));
                default -> { }
            }
        }

        long rowBytes = after.values().stream().mapToLong(ColumnInfo::rowBytes).sum();
        long before = columns.values().stream().mapToLong(ColumnInfo::rowBytes).sum();
        if (rowBytes > MAX_ROW_BYTES) {
            checks.add(new Check(Severity.BLOCK, "Row size over MySQL's limit",
                "After the change the columns need about " + rowBytes + " bytes per row, over the 65,535-byte "
                    + "limit (TEXT and BLOB count about 12 bytes each). MySQL will refuse it; use TEXT for long "
                    + "columns."));
        } else if (rowBytes > before && rowBytes > MAX_ROW_BYTES * 0.8) {
            checks.add(new Check(Severity.WARN, "Row size near MySQL's limit",
                "About " + rowBytes + " of 65,535 bytes per row after the change."));
        } else {
            checks.add(new Check(Severity.OK, "Row size within the limit",
                "About " + rowBytes + " of 65,535 bytes per row after the change."));
        }

        boolean compact = facts.rowFormat() != null
            && (facts.rowFormat().equalsIgnoreCase("COMPACT") || facts.rowFormat().equalsIgnoreCase("REDUNDANT"));
        for (IndexShape index : indexes) {
            long key = 0;
            boolean overPart = false;
            for (IndexPart part : index.parts()) {
                ColumnInfo column = part.functional() ? null : after.get(lower(part.column()));
                if (column == null) {
                    continue;
                }
                long bytes = column.keyBytes(part.prefix());
                key += bytes;
                overPart |= compact && bytes > MAX_KEY_PART_BYTES_COMPACT;
            }
            if (key > MAX_KEY_BYTES || overPart) {
                String limit = compact ? "767 bytes per column (" + facts.rowFormat() + " rows)" : "3,072 bytes";
                if (index.added()) {
                    checks.add(new Check(Severity.BLOCK, "Index " + index.name() + " over the key length limit",
                        "Its key needs about " + key + " bytes, over InnoDB's " + limit + ". Index a prefix, "
                            + "e.g. name(191)."));
                } else {
                    checks.add(new Check(Severity.WARN, "Index " + index.name() + " may exceed the key length limit",
                        "With the new column sizes its key would need about " + key + " bytes, over InnoDB's "
                            + limit + ", unless it already indexes a prefix. The snapshot does not record prefix "
                            + "lengths: check SHOW INDEX (Sub_part)."));
                    unknowns.add("Index prefix lengths are not in the snapshot.");
                }
            }
        }
    }

    private void collations(ParsedAlter parsed, Map<String, ColumnInfo> columns, TableStructure structure,
                            List<Check> checks) {
        Map<String, String[]> changed = new LinkedHashMap<>();
        for (Clause clause : parsed.clauses()) {
            if (clause.kind() == Kind.MODIFY_COLUMN || clause.kind() == Kind.CHANGE_COLUMN) {
                ColumnInfo current = columns.get(lower(clause.name()));
                ColumnDef next = clause.column();
                if (current != null && current.isString() && (next.charset() != null || next.collation() != null)) {
                    changed.put(lower(clause.name()), new String[] {current.charset(),
                        next.charset() != null ? next.charset() : charsetOf(next.collation()), next.collation()});
                }
            } else if (clause.kind() == Kind.CONVERT_CHARSET) {
                columns.values().stream().filter(ColumnInfo::isString).forEach(c ->
                    changed.put(lower(c.name()), new String[] {c.charset(), clause.value(), clause.collation()}));
            }
        }
        if (changed.isEmpty()) {
            return;
        }
        List<String> fromLatin1 = changed.entrySet().stream()
            .filter(e -> "latin1".equalsIgnoreCase(e.getValue()[0]) && e.getValue()[1] != null
                && e.getValue()[1].toLowerCase(Locale.ROOT).startsWith("utf8"))
            .map(Map.Entry::getKey).toList();
        if (!fromLatin1.isEmpty()) {
            checks.add(new Check(Severity.WARN, "latin1 → UTF-8 conversion transcodes the bytes",
                "ALTER converts each latin1 character to UTF-8 (" + String.join(", ", fromLatin1) + "). If these "
                    + "columns hold UTF-8 bytes written through a latin1 connection, which shows as mojibake to "
                    + "other readers, the conversion double-encodes them. Convert through VARBINARY, or with a "
                    + "shadow column and CONVERT(CAST(col AS BINARY) USING utf8mb4), instead."));
        }
        for (TableIndex index : structure.indices() == null ? List.<TableIndex>of() : structure.indices()) {
            if (!index.unique()) {
                continue;
            }
            List<String> hit = index.columns().stream().filter(c -> changed.containsKey(lower(c))).toList();
            if (!hit.isEmpty()) {
                checks.add(new Check(Severity.WARN, "Unique index " + index.name() + " under a new collation",
                    "Values that are distinct today may compare equal under the new collation (accents, case, "
                        + "trailing spaces under NO PAD collations), and the change then fails with a duplicate "
                        + "key. Check on a replica first: GROUP BY " + String.join(", ", hit)
                        + " COLLATE <new collation> HAVING COUNT(*) > 1."));
            }
        }
    }

    private void foreignKeys(TableFacts facts, Algorithm overall, boolean rebuilds, List<Check> checks) {
        if (!facts.childOfForeignKey() && facts.referencedBy().isEmpty()) {
            return;
        }
        String role = facts.childOfForeignKey() && !facts.referencedBy().isEmpty() ? "has foreign keys and is "
            + "referenced by " + String.join(", ", facts.referencedBy())
            : facts.childOfForeignKey() ? "has foreign keys"
            : "is referenced by foreign keys from " + String.join(", ", facts.referencedBy());
        boolean needsTool = overall == Algorithm.COPY || rebuilds;
        checks.add(new Check(needsTool ? Severity.WARN : Severity.INFO, "Foreign keys",
            "The table " + role + ". gh-ost does not support tables in foreign keys, as parent or child. "
                + (facts.referencedBy().isEmpty() ? "pt-online-schema-change handles it."
                : "pt-online-schema-change needs --alter-foreign-keys-method (prefer rebuild_constraints) to "
                    + "repoint the referencing tables.")));
    }

    /**
     * The other tables a change to this one reaches through foreign keys, as MySQL 8.4 takes its metadata locks
     * (with foreign_key_checks on, the default):
     *
     * <ul>
     *   <li>At the end of any ALTER, CREATE INDEX or DROP INDEX, an exclusive lock on every table that
     *       references this one ({@code collect_fk_children}), and on the parents of keys added or dropped, or
     *       of every key when the table is renamed. Every query on those tables waits.</li>
     *   <li>Every UPDATE or DELETE on a table this one references, and every INSERT or UPDATE on one that
     *       references it, takes a metadata lock on this table for its whole transaction, to check or cascade
     *       through the key ({@code process_table_fks}). Those wait behind the change, and an open transaction
     *       that ran one holds the change up until it commits.</li>
     * </ul>
     */
    List<LockedTable> lockedTables(ParsedAlter parsed, TableStructure structure, double longStatementMs) {
        Map<String, LockedTable> out = new LinkedHashMap<>();
        boolean renames = parsed.clauses().stream().anyMatch(c -> c.kind() == Kind.RENAME_TABLE);
        for (Clause clause : parsed.clauses()) {
            if (clause.kind() == Kind.ADD_FOREIGN_KEY && clause.referencedTable() != null) {
                String[] parts = clause.referencedTable().split("\\.", 2);
                String schema = parts.length == 2 ? parts[0] : structure.schema();
                String table = parts.length == 2 ? parts[1] : parts[0];
                if (!(table.equalsIgnoreCase(structure.name()) && schema.equalsIgnoreCase(structure.schema()))) {
                    add(out, schema, table, Role.PARENT, Reach.EVERY_QUERY, clause.name(),
                        "Adding a foreign key to it takes an exclusive lock on it.", longStatementMs);
                }
            }
            if (clause.kind() == Kind.DROP_FOREIGN_KEY || clause.kind() == Kind.DROP_CONSTRAINT) {
                ownForeignKeys(structure).stream()
                    .filter(fk -> fk.constraintName() != null && fk.constraintName().equalsIgnoreCase(clause.name()))
                    .findFirst()
                    .map(fk -> fk.to().get(0).getTable())
                    .filter(parent -> !sameTableRef(parent, structure))
                    .ifPresent(parent -> add(out, schemaOf(parent, structure), parent.getNormalizedTableName(),
                        Role.PARENT, Reach.EVERY_QUERY, clause.name(),
                        "Dropping its foreign key takes an exclusive lock on it.", longStatementMs));
            }
        }
        for (ForeignKey fk : ownForeignKeys(structure)) {
            TableReference parent = fk.to().get(0).getTable();
            if (sameTableRef(parent, structure)) {
                continue;
            }
            if (renames) {
                add(out, schemaOf(parent, structure), parent.getNormalizedTableName(), Role.PARENT,
                    Reach.EVERY_QUERY, fk.constraintName(),
                    "Renaming a table takes an exclusive lock on every table it references.", longStatementMs);
            } else {
                add(out, schemaOf(parent, structure), parent.getNormalizedTableName(), Role.PARENT, Reach.WRITES,
                    fk.constraintName(), "This table references it, so each UPDATE or DELETE there locks this "
                        + "table to check or cascade through the key: those wait behind the change, and a "
                        + "transaction that ran one holds the change up until it commits.", longStatementMs);
            }
        }
        for (Map.Entry<TableReference, List<String>> child : referencingTables(structure).entrySet()) {
            TableReference ref = child.getKey();
            for (String constraint : child.getValue().isEmpty() ? Collections.<String>singletonList(null)
                : child.getValue()) {
                add(out, schemaOf(ref, structure), ref.getNormalizedTableName(), Role.CHILD, Reach.EVERY_QUERY,
                    constraint, "It references this table, so the change takes an exclusive lock on it as well, "
                        + "and its INSERTs and UPDATEs lock this table to check the key.", longStatementMs);
            }
        }
        return out.values().stream()
            .sorted(Comparator.comparing((LockedTable t) -> t.reach() != Reach.EVERY_QUERY))
            .toList();
    }

    private void add(Map<String, LockedTable> out, String schema, String table, Role role, Reach reach,
                     String constraint, String reason, double longStatementMs) {
        String key = lower(schema) + "." + lower(table);
        LockedTable seen = out.get(key);
        if (seen != null) {
            if (constraint != null && !seen.constraints().contains(constraint)) {
                List<String> constraints = new ArrayList<>(seen.constraints());
                constraints.add(constraint);
                out.put(key, new LockedTable(seen.schema(), seen.table(), seen.role(), seen.reach(), seen.waits(),
                    List.copyOf(constraints), seen.reason(), seen.workload()));
            }
            return;
        }
        TableReference reference = TableReference.newTableReference(schema, table);
        boolean known = schemaService.getTable(reference) != null;
        String waits = reach == Reach.EVERY_QUERY ? "every query" : "UPDATE and DELETE";
        out.put(key, new LockedTable(schema, table, role, reach, waits,
            constraint == null ? List.of() : List.of(constraint), reason,
            workload(reference, longStatementMs, known)));
    }

    private static List<ForeignKey> ownForeignKeys(TableStructure structure) {
        return structure.foreignKeys() == null ? List.of() : structure.foreignKeys().stream()
            .filter(fk -> !fk.from().isEmpty() && !fk.to().isEmpty() && sameTable(fk.from().get(0), structure))
            .toList();
    }

    private Map<TableReference, List<String>> referencingTables(TableStructure structure) {
        Map<String, TableReference> refs = new LinkedHashMap<>();
        Map<String, List<String>> names = new LinkedHashMap<>();
        BiConsumer<TableReference, String> note = (ref, name) -> {
            String key = lower(schemaOf(ref, structure)) + "." + lower(ref.getNormalizedTableName());
            refs.putIfAbsent(key, ref);
            List<String> list = names.computeIfAbsent(key, k -> new ArrayList<>());
            if (name != null && !list.contains(name)) {
                list.add(name);
            }
        };
        for (TableStructure other : schemaService.getTables()) {
            if (other.foreignKeys() == null || sameName(other, structure)) {
                continue;
            }
            for (ForeignKey fk : other.foreignKeys()) {
                if (fk.to().stream().anyMatch(c -> sameTable(c, structure))
                    && fk.from().stream().anyMatch(c -> sameTable(c, other))) {
                    note.accept(TableReference.newTableReference(other.schema(), other.name()), fk.constraintName());
                }
            }
        }
        if (structure.foreignKeys() != null) {
            for (ForeignKey fk : structure.foreignKeys()) {
                if (fk.to().stream().anyMatch(c -> sameTable(c, structure))) {
                    fk.from().stream().filter(c -> !sameTable(c, structure)).findFirst()
                        .ifPresent(c -> note.accept(c.getTable(), fk.constraintName()));
                }
            }
        }
        Map<TableReference, List<String>> out = new LinkedHashMap<>();
        refs.forEach((key, ref) -> out.put(ref, names.get(key)));
        return out;
    }

    private static String schemaOf(TableReference ref, TableStructure fallback) {
        return ref.getSchemaName() == null ? fallback.schema() : ref.getSchemaName();
    }

    private static boolean sameTableRef(TableReference ref, TableStructure table) {
        return ref != null && ref.getNormalizedTableName() != null
            && ref.getNormalizedTableName().equalsIgnoreCase(table.name())
            && (ref.getSchemaName() == null || ref.getSchemaName().equalsIgnoreCase(table.schema()));
    }

    private static void lockedTablesCheck(List<LockedTable> locked, List<Check> checks) {
        if (locked.isEmpty()) {
            return;
        }
        List<LockedTable> all = locked.stream().filter(t -> t.reach() == Reach.EVERY_QUERY).toList();
        List<LockedTable> writes = locked.stream().filter(t -> t.reach() == Reach.WRITES).toList();
        List<String> parts = new ArrayList<>();
        if (!all.isEmpty()) {
            parts.add("MySQL also takes an exclusive metadata lock on " + names(all) + ": while the statement waits "
                + "for " + (all.size() == 1 ? "its" : "their") + " open transactions, every query arriving there "
                + "waits behind it.");
        }
        if (!writes.isEmpty()) {
            parts.add("Each UPDATE or DELETE on " + names(writes) + " locks this table to check or cascade "
                + "through the foreign key, so those writes wait behind the change too, and a transaction that "
                + "ran one holds the change up until it commits.");
        }
        parts.add("This holds even when the change itself is instant. SET foreign_key_checks = 0 in the session "
            + "running the change does not avoid it: MySQL takes these locks to keep the foreign key metadata "
            + "consistent whatever the setting, and the waiting writes take theirs under their own sessions' "
            + "settings.");
        LockedTable slowest = locked.stream().filter(t -> t.workload() != null)
            .max(Comparator.comparingDouble(t -> t.workload().maxTimeMs())).orElse(null);
        if (slowest != null && slowest.workload().maxTimeMs() > 0) {
            parts.add("The slowest captured statement on them ran " + seconds(slowest.workload().maxTimeMs())
                + " (" + slowest.table() + ").");
        }
        boolean anyLong = locked.stream().anyMatch(t -> t.workload() != null && t.workload().longStatements() > 0);
        checks.add(new Check(anyLong ? Severity.WARN : Severity.INFO,
            "Metadata locks reach " + locked.size() + (locked.size() == 1 ? " other table" : " other tables"),
            String.join(" ", parts)));
    }

    private static String names(List<LockedTable> tables) {
        return tables.stream().map(t -> t.schema() + "." + t.table()).collect(Collectors.joining(", "));
    }

    private List<String> recommendations(Algorithm overall, boolean rebuilds, boolean concurrent,
                                         String requestedAlgorithm, TableFacts facts, Workload workload,
                                         List<LockedTable> locked) {
        List<String> out = new ArrayList<>();
        boolean large = facts.sizeBytes() != null && facts.sizeBytes() > LARGE_TABLE_BYTES
            || facts.rowsEstimate() != null && facts.rowsEstimate() > LARGE_TABLE_ROWS;
        String lockTimeout = "Run it with SET SESSION lock_wait_timeout = 2 and retry on timeout: every DDL takes "
            + "the table's metadata lock, waits behind running statements, and makes every later query wait "
            + "behind it.";
        if (workload != null && workload.longStatements() > 0) {
            lockTimeout += " Here " + workload.longStatements() + " captured statement"
                + (workload.longStatements() == 1 ? " has" : "s have") + " run longer than "
                + seconds(workload.longThresholdMs()) + " (slowest " + seconds(workload.maxTimeMs())
                + "); check the process list first and run at a quiet time.";
        }
        if (!locked.isEmpty()) {
            lockTimeout += " The locks also reach " + locked.stream().map(LockedTable::table)
                .collect(Collectors.joining(", ")) + " through foreign keys: look for long transactions there too "
                + "(sys.schema_table_lock_waits, information_schema.INNODB_TRX).";
        }
        switch (overall) {
            case INSTANT -> {
                out.add("Run it natively" + (requestedAlgorithm == null ? ", with ALGORITHM=INSTANT" : "") + ".");
                out.add(lockTimeout);
            }
            case INPLACE -> {
                out.add("Run it natively" + (requestedAlgorithm == null ? ", with ALGORITHM=INPLACE, LOCK=NONE" : "")
                    + ": writes continue on the primary.");
                out.add(lockTimeout);
                if (large) {
                    out.add("Replicas apply it only after the primary finishes, and lag for as long as it took. For "
                        + "a table this size, consider pt-online-schema-change, which throttles on replica lag.");
                }
            }
            case INPLACE_REBUILD -> {
                out.add("It rewrites the whole table in place" + size(facts) + "; writes continue on the primary.");
                out.add(lockTimeout);
                out.add(large ? "Replicas lag for the whole rebuild. For a table this size, use "
                    + "pt-online-schema-change to throttle on replica lag."
                    : "Replicas lag for the duration of the rebuild.");
            }
            case COPY -> {
                out.add("MySQL copies the table" + size(facts) + " and blocks writes until it finishes. Use "
                    + "pt-online-schema-change instead, which copies in chunks while writes continue.");
                if (workload != null && workload.writesPerDay() > 0) {
                    out.add("The table takes about " + Math.round(workload.writesPerDay()) + " writes a day; "
                        + "pt-online-schema-change's triggers add to each of them while it runs.");
                }
            }
            default -> out.add("The algorithm could not be predicted. Run it first with an explicit ALGORITHM= on a "
                + "copy of the table, such as the weekly snapshot host, to see what MySQL accepts.");
        }
        if (!concurrent && overall != Algorithm.COPY) {
            out.add("A clause blocks writes while it runs (LOCK=SHARED); schedule it when writes can wait.");
        }
        return out;
    }

    private TableFacts facts(TableStructure structure, TableReference reference) {
        if (structure == null) {
            return new TableFacts(false, null, null, null, null, 0, false, List.of());
        }
        List<TableStats> stats = structure.stats() == null ? List.of() : structure.stats();
        Long rows = stats.stream().map(TableStats::rowsEstimate).filter(Objects::nonNull)
            .reduce(Long::sum).orElse(null);
        Long size = stats.stream().map(TableStats::totalLength).filter(Objects::nonNull)
            .reduce(Long::sum).orElse(null);
        String rowFormat = stats.stream().map(TableStats::rowFormat).filter(Objects::nonNull).findFirst().orElse(null);
        Set<String> collations = new LinkedHashSet<>();
        stats.forEach(s -> {
            if (s.tableCollation() != null) {
                collations.add(s.tableCollation());
            }
        });
        boolean child = structure.foreignKeys() != null && structure.foreignKeys().stream()
            .anyMatch(fk -> fk.from().stream().anyMatch(c -> sameTable(c, structure)));
        List<String> referencedBy = new ArrayList<>();
        for (TableStructure other : schemaService.getTables()) {
            if (other.foreignKeys() == null || (sameName(other, structure))) {
                continue;
            }
            for (ForeignKey fk : other.foreignKeys()) {
                if (fk.to().stream().anyMatch(c -> sameTable(c, structure))
                    && fk.from().stream().anyMatch(c -> sameTable(c, other))) {
                    referencedBy.add(other.schema() + "." + other.name());
                    break;
                }
            }
        }
        if (structure.foreignKeys() != null) {
            for (ForeignKey fk : structure.foreignKeys()) {
                if (fk.to().stream().anyMatch(c -> sameTable(c, structure))) {
                    fk.from().stream().filter(c -> !sameTable(c, structure)).findFirst().ifPresent(c ->
                        referencedBy.add(c.getTable().getSchemaName() + "." + c.getTable().getNormalizedTableName()));
                }
            }
        }
        return new TableFacts(true, rows, size, rowFormat, collations.size() == 1 ? collations.iterator().next() : null,
            stats.size(), child, referencedBy.stream().distinct().sorted().toList());
    }

    private Workload workload(TableReference reference, double longStatementMs, boolean known) {
        if (!known) {
            return null;
        }
        WriteRate writes = workloadService.getWriteRate(reference);
        LockRisk risk = workloadService.getLockRisk(reference, longStatementMs);
        return new Workload(writes.executionsPerDay(), writes.rowsPerDay(), risk.longThresholdMs(),
            risk.longStatements(), risk.longExecutionsPerDay(), risk.maxTimeMs());
    }

    private static boolean sameTable(ColumnReference column, TableStructure table) {
        TableReference ref = column.getTable();
        return ref != null && ref.getNormalizedTableName() != null
            && ref.getNormalizedTableName().equalsIgnoreCase(table.name())
            && (ref.getSchemaName() == null || ref.getSchemaName().equalsIgnoreCase(table.schema()));
    }

    private static boolean sameName(TableStructure a, TableStructure b) {
        return a.name().equalsIgnoreCase(b.name()) && Objects.equals(lower(a.schema()), lower(b.schema()));
    }

    private static boolean indexed(TableStructure structure, String column) {
        return structure != null && structure.indices() != null && structure.indices().stream()
            .anyMatch(i -> i.columns().stream().anyMatch(c -> c.equalsIgnoreCase(column)));
    }

    private static Map<String, ColumnInfo> columnsOf(TableStructure structure) {
        Map<String, ColumnInfo> columns = new LinkedHashMap<>();
        if (structure != null && structure.columns() != null) {
            structure.columns().forEach(c -> columns.put(lower(c.columnName()), ColumnInfo.of(c)));
        }
        return columns;
    }

    private static ColumnInfo merge(ColumnInfo old, ColumnDef next) {
        ColumnInfo fresh = ColumnInfo.of(next);
        if (old == null) {
            return fresh;
        }
        String charset = next.charset() != null ? next.charset()
            : next.collation() != null ? charsetOf(next.collation()) : fresh.isString() ? old.charset() : null;
        return new ColumnInfo(fresh.name(), fresh.type(), fresh.length(), fresh.scale(), charset,
            next.collation() != null ? next.collation() : old.collation(), fresh.nullable());
    }

    record ColumnInfo(String name, String type, Integer length, Integer scale, String charset, String collation,
                      boolean nullable) {

        static ColumnInfo of(TableColumn column) {
            String type = column.typeName() == null ? "" : column.typeName().toUpperCase(Locale.ROOT).split("\\s")[0];
            return new ColumnInfo(column.columnName(), type, column.columnSize(), column.decimalDigits(),
                column.characterSet(), column.collation(), !"NO".equalsIgnoreCase(column.isNullable()));
        }

        static ColumnInfo of(ColumnDef def) {
            String charset = def.charset() != null ? def.charset() : charsetOf(def.collation());
            boolean string = isStringType(def.type());
            return new ColumnInfo(def.name(), def.type(), def.length(), def.scale(),
                string && charset == null ? "utf8mb4" : charset, def.collation(),
                def.nullable() == null || def.nullable());
        }

        ColumnInfo withCharset(String newCharset, String newCollation) {
            return new ColumnInfo(name, type, length, scale, newCharset, newCollation, nullable);
        }

        ColumnInfo renamed(String newName) {
            return new ColumnInfo(newName, type, length, scale, charset, collation, nullable);
        }

        boolean isString() {
            return isStringType(type);
        }

        long rowBytes() {
            int len = length == null ? 0 : length;
            return switch (baseType(type)) {
                case "VARCHAR" -> varBytes((long) len * bytesPerChar(charset));
                case "CHAR" -> (long) len * bytesPerChar(charset);
                case "VARBINARY" -> varBytes(len);
                case "BINARY", "BIT" -> "BIT".equals(baseType(type)) ? (len + 7) / 8 : len;
                case "TINYINT", "BOOLEAN", "BOOL", "YEAR" -> 1;
                case "SMALLINT" -> 2;
                case "MEDIUMINT", "DATE" -> 3;
                case "INT", "INTEGER", "FLOAT", "TIMESTAMP" -> 4;
                case "BIGINT", "DOUBLE", "REAL", "SET" -> 8;
                case "DATETIME" -> 8;
                case "TIME" -> 6;
                case "DECIMAL", "NUMERIC" -> (long) Math.ceil(Math.max(len, 1) * 4.0 / 9) + 1;
                case "ENUM" -> 2;
                default -> 12; // TEXT, BLOB, JSON, GEOMETRY: a pointer in the row
            };
        }

        long keyBytes(Integer prefix) {
            if (isString()) {
                int chars = prefix != null ? prefix : length == null ? 0 : length;
                return (long) chars * bytesPerChar(charset);
            }
            if ("VARBINARY".equals(baseType(type)) || "BINARY".equals(baseType(type))) {
                return prefix != null ? prefix : length == null ? 0 : length;
            }
            return Math.min(rowBytes(), 8);
        }
    }

    private record IndexShape(String name, boolean unique, List<IndexPart> parts, boolean added) { }

    static int bytesPerChar(String charset) {
        if (charset == null) {
            return 4;
        }
        return switch (charset.toLowerCase(Locale.ROOT)) {
            case "latin1", "ascii", "binary", "latin2", "cp1250", "cp1251", "cp1256", "cp1257", "greek", "hebrew",
                 "tis620", "swe7", "koi8r", "koi8u", "armscii8", "geostd8", "dec8", "cp850", "cp852", "cp866",
                 "hp8", "keybcs2", "macce", "macroman" -> 1;
            case "ucs2", "gbk", "big5", "sjis", "cp932", "euckr" -> 2;
            case "utf8", "utf8mb3", "ujis", "eucjpms", "gb2312" -> 3;
            default -> 4;
        };
    }

    static String charsetOf(String collation) {
        if (collation == null) {
            return null;
        }
        int at = collation.indexOf('_');
        return (at < 0 ? collation : collation.substring(0, at)).toLowerCase(Locale.ROOT);
    }

    private static boolean isStringType(String type) {
        String base = baseType(type);
        return base.equals("VARCHAR") || base.equals("CHAR") || base.endsWith("TEXT")
            || base.equals("ENUM") || base.equals("SET");
    }

    private static boolean isVarchar(String type) {
        return baseType(type).equals("VARCHAR");
    }

    private static String baseType(String type) {
        return type == null ? "" : type.toUpperCase(Locale.ROOT).split("[\\s(]")[0];
    }

    private static long varBytes(long bytes) {
        return bytes + (bytes > 255 ? 2 : 1);
    }

    private static int rank(String algorithm) {
        return switch (algorithm.toUpperCase(Locale.ROOT)) {
            case "INSTANT" -> 0;
            case "INPLACE" -> 1;
            case "COPY" -> 2;
            case "DEFAULT" -> -1;
            default -> -1;
        };
    }

    private static String sqlAlgorithm(Algorithm algorithm) {
        return switch (algorithm) {
            case INSTANT -> "INSTANT";
            case INPLACE, INPLACE_REBUILD -> "INPLACE";
            case COPY -> "COPY";
            case UNKNOWN -> "DEFAULT";
        };
    }

    static String label(Algorithm algorithm) {
        return switch (algorithm) {
            case INSTANT -> "INSTANT";
            case INPLACE -> "INPLACE";
            case INPLACE_REBUILD -> "INPLACE with a table rebuild";
            case COPY -> "COPY";
            case UNKNOWN -> "an unknown algorithm";
        };
    }

    private static String size(TableFacts facts) {
        if (facts.sizeBytes() == null && facts.rowsEstimate() == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        if (facts.rowsEstimate() != null) {
            parts.add("≈" + compact(facts.rowsEstimate()) + " rows");
        }
        if (facts.sizeBytes() != null) {
            parts.add(String.format(Locale.ROOT, "%.1f GB", facts.sizeBytes() / 1e9));
        }
        return " (" + String.join(", ", parts) + ")";
    }

    private static String compact(long n) {
        if (n >= 1_000_000_000L) {
            return String.format(Locale.ROOT, "%.1fB", n / 1e9);
        }
        if (n >= 1_000_000L) {
            return String.format(Locale.ROOT, "%.1fM", n / 1e6);
        }
        if (n >= 1_000L) {
            return String.format(Locale.ROOT, "%.0fk", n / 1e3);
        }
        return Long.toString(n);
    }

    private static String seconds(double ms) {
        return ms >= 60_000 ? String.format(Locale.ROOT, "%.0f min", ms / 60_000)
            : String.format(Locale.ROOT, "%.0f s", ms / 1000);
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }
}
