package com.github.safetytrick.sqlexplorer.profiling;

import java.sql.JDBCType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.github.safetytrick.sqlexplorer.environment.SamplingLimits;
import com.github.safetytrick.sqlexplorer.schema.TableColumn;
import com.github.safetytrick.sqlexplorer.schema.TableIndex;
import com.github.safetytrick.sqlexplorer.schema.TableStructure;

/**
 * How one table's character columns will be sampled (sqlx-n5j.3): by which
 * key, through which windows, and how many rows that can read at most. Pure —
 * worked out from the schema snapshot, so it can be shown before anything
 * runs.
 *
 * <p>Sampling walks an index, never {@code LIMIT} on a scan, so it sees the
 * whole life of the table: the first and last rows by key (oldest and newest)
 * and evenly spaced windows between. The windows are placed so none overlaps
 * another, which keeps every count additive and lets a small table be read
 * whole and said to be.
 */
public record ProfilePlan(String schema, String table, Method method, List<String> key, boolean integerKey,
                          List<ProfiledColumn> columns, String fullScanColumn, String unavailable) {

    /** How rows are reached. */
    public enum Method {
        /** First and last rows by a single-column integer key, and windows seeked between. */
        WINDOWS,
        /** First and last rows only: the key is composite or not an integer, so no key between can be computed. */
        ENDS,
        /** Every row, in key order, chunk by chunk: one column, confirmed by a person. */
        FULL_SCAN,
        /** No primary or unique key to walk: nothing is read. */
        NONE
    }

    /** A column to profile, with the signatures its character set gives meaning to. */
    public record ProfiledColumn(String name, String type, String characterSet, String collation,
                                 List<Signature> signatures) { }

    public static final int HEAD_ROWS = 5_000;
    public static final int TAIL_ROWS = 5_000;
    public static final int MIDDLE_WINDOWS = 10;
    public static final int MIDDLE_ROWS = 1_000;
    public static final int FULL_SCAN_CHUNK = 5_000;
    /** Row keys kept per signature, per server: enough to look at, never the values. */
    public static final int IDS_PER_SIGNATURE = 20;
    /** Agreed with the user: two seconds a statement, no chunk larger than the head window. */
    public static final SamplingLimits LIMITS = new SamplingLimits(Duration.ofSeconds(2), HEAD_ROWS);
    /** Between one statement and the next on the same server. */
    public static final Duration PAUSE = Duration.ofMillis(200);

    private static final Set<String> TEXT_TYPES = Set.of(
        "char", "varchar", "tinytext", "text", "mediumtext", "longtext");
    private static final Set<JDBCType> INTEGER_TYPES = Set.of(
        JDBCType.TINYINT, JDBCType.SMALLINT, JDBCType.INTEGER, JDBCType.BIGINT);

    /**
     * The plan for a table.
     *
     * @param only           the columns to profile; empty for every character column
     * @param fullScanColumn the one column to read in full, or null to sample
     */
    public static ProfilePlan of(TableStructure table, List<String> only, String fullScanColumn) {
        List<ProfiledColumn> columns = new ArrayList<>();
        for (TableColumn column : table.columns().stream()
            .sorted((a, b) -> Integer.compare(a.ordinal(), b.ordinal())).toList()) {
            if (!isText(column)) {
                continue;
            }
            boolean wanted = fullScanColumn != null ? column.columnName().equalsIgnoreCase(fullScanColumn)
                : only == null || only.isEmpty() || only.stream().anyMatch(c -> c.equalsIgnoreCase(column.columnName()));
            if (wanted) {
                columns.add(new ProfiledColumn(column.columnName(), declared(column), column.characterSet(),
                    column.collation(), Signature.forCharacterSet(column.characterSet())));
            }
        }
        List<String> key = keyOf(table);
        boolean integer = key.size() == 1 && table.columns().stream()
            .anyMatch(c -> c.columnName().equalsIgnoreCase(key.getFirst()) && INTEGER_TYPES.contains(c.type()));
        String unavailable = null;
        Method method;
        if ("VIEW".equalsIgnoreCase(table.type()) || table.underlyingTables() != null) {
            method = Method.NONE;
            unavailable = "a view has no rows of its own; profile the tables it reads";
        } else if (columns.isEmpty()) {
            method = Method.NONE;
            unavailable = fullScanColumn != null ? fullScanColumn + " is not a character column of this table"
                : "the table has no character columns to profile";
        } else if (key.isEmpty()) {
            method = Method.NONE;
            unavailable = "the table has no primary key or unique index on non-null columns, so there is no "
                + "index to walk; sampling would mean scanning";
        } else if (fullScanColumn != null) {
            method = Method.FULL_SCAN;
            if (key.size() > 1) {
                unavailable = "a full scan walks a single-column key in chunks, and this table's key is composite";
            }
        } else {
            method = integer ? Method.WINDOWS : Method.ENDS;
        }
        return new ProfilePlan(table.schema(), table.name(), method, key, integer, List.copyOf(columns),
            fullScanColumn, unavailable);
    }

    public boolean runnable() {
        return unavailable == null && method != Method.NONE;
    }

    /**
     * The most distinct rows one server's run can read: every window full.
     * Not lowered by the row estimate, which is only an estimate; a full scan
     * reads the whole table, so for it the estimate is the answer (-1 unknown).
     */
    public long maxRows(Long rowsEstimate) {
        return switch (method) {
            case WINDOWS -> HEAD_ROWS + TAIL_ROWS + (long) MIDDLE_WINDOWS * MIDDLE_ROWS;
            case ENDS -> HEAD_ROWS + TAIL_ROWS;
            case FULL_SCAN -> rowsEstimate == null ? -1 : rowsEstimate;
            case NONE -> 0;
        };
    }

    /** How many windows (statements reading rows) one server's run issues at most, before the follow-ups. */
    public long maxWindows(Long rowsEstimate) {
        return switch (method) {
            case WINDOWS -> 2 + MIDDLE_WINDOWS;
            case ENDS -> 2;
            case FULL_SCAN -> rowsEstimate == null ? -1 : rowsEstimate / FULL_SCAN_CHUNK + 1;
            case NONE -> 0;
        };
    }

    /**
     * Statements one server's run issues at most: the key bounds, each window,
     * and per window one follow-up per column that found characters to list.
     */
    public long maxStatements(Long rowsEstimate) {
        long windows = maxWindows(rowsEstimate);
        if (windows < 0) {
            return -1;
        }
        long listing = columns.stream().anyMatch(c -> c.signatures().stream().anyMatch(Signature::listsCharacters))
            ? columns.size() : 0;
        return (method == Method.WINDOWS ? 1 : 0) + windows * (1 + listing);
    }

    /** The primary key, else the first unique index whose columns are all NOT NULL. */
    static List<String> keyOf(TableStructure table) {
        List<TableIndex> indexes = table.indices() == null ? List.of() : table.indices();
        for (TableIndex index : indexes) {
            if ("PRIMARY".equalsIgnoreCase(index.name()) && !index.columns().isEmpty()) {
                return List.copyOf(index.columns());
            }
        }
        for (TableIndex index : indexes) {
            if (index.unique() && !index.columns().isEmpty() && index.columns().stream().allMatch(name ->
                table.columns().stream().anyMatch(c -> c.columnName().equalsIgnoreCase(name)
                    && "NO".equalsIgnoreCase(c.isNullable())))) {
                return List.copyOf(index.columns());
            }
        }
        return List.of();
    }

    private static boolean isText(TableColumn column) {
        String type = column.typeName() == null ? "" : column.typeName().toLowerCase(Locale.ROOT);
        String charset = column.characterSet();
        return TEXT_TYPES.contains(type) && charset != null && !"binary".equalsIgnoreCase(charset);
    }

    private static String declared(TableColumn column) {
        String type = column.typeName().toUpperCase(Locale.ROOT);
        return type.endsWith("CHAR") ? type + "(" + column.columnSize() + ")" : type;
    }
}
