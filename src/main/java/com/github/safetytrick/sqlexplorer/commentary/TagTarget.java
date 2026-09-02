package com.github.safetytrick.sqlexplorer.commentary;

import java.util.Objects;

/**
 * What a tag points at, as the API states it.
 *
 * <p>WHY THIS REPLACED A PATH STRING ON THE WIRE. `sqlx-11v` made the storage
 * typed, which removed the parsing and the unique-key prefix — but the API still
 * took `/schema/{s}/table/{t}`, so the format remained the thing being parsed
 * and a table whose name contains {@code /} still could not be addressed at all.
 * MySQL permits such a name; the path format cannot represent one; and no amount
 * of typed storage fixes a wire format that cannot say what it means (sqlx-3ai).
 *
 * <p>THE PARTS A TARGET DOES NOT USE ARE EMPTY, NEVER NULL, matching the
 * columns: MySQL treats NULLs as distinct in a unique index, so the storage
 * cannot use them and neither should the value that maps onto it.
 *
 * @param type   {@link #SCHEMA}, {@link #TABLE} or {@link #QUERY}
 * @param schema the schema, for a schema or table target
 * @param table  the table, for a table target
 * @param digest the normalized digest, for a query target
 */
public record TagTarget(String type, String schema, String table, String digest) {

    public static final String SCHEMA = "schema";
    public static final String TABLE = "table";
    public static final String QUERY = "query";

    public static TagTarget ofSchema(String schema) {
        return new TagTarget(SCHEMA, schema, "", "").normalized();
    }

    public static TagTarget ofTable(String schema, String table) {
        return new TagTarget(TABLE, schema, table, "").normalized();
    }

    public static TagTarget ofQuery(String digest) {
        return new TagTarget(QUERY, "", "", digest).normalized();
    }

    /** The same target with nulls collapsed to empty, ready to store or match. */
    public TagTarget normalized() {
        return new TagTarget(type, empty(schema), empty(table), empty(digest));
    }

    /**
     * This target, checked.
     *
     * <p>Rejects a shape that cannot address anything — an unknown type, or a
     * kind missing the part that identifies it. A target is client-supplied, and
     * an unchecked one produces a row nothing will ever look for, which is the
     * defect the path format kept producing (sqlx-0lj).
     *
     * @throws IllegalArgumentException naming what was wrong
     */
    public TagTarget validated() {
        validate();
        return normalized();
    }

    /**
     * Check this target, discarding the result.
     *
     * <p>Beside {@link #validated()} so a caller that only wants the check does
     * not have to invent a use for the value it returns.
     *
     * @throws IllegalArgumentException naming what was wrong
     */
    public void validate() {
        TagTarget target = normalized();
        if (!SCHEMA.equals(target.type) && !TABLE.equals(target.type) && !QUERY.equals(target.type)) {
            throw new IllegalArgumentException(
                "type must be " + SCHEMA + ", " + TABLE + " or " + QUERY + ", not " + target.type);
        }
        if (QUERY.equals(target.type)) {
            require(target.digest, "a query target needs a digest");
            forbid(target.schema, "a query target has no schema");
            forbid(target.table, "a query target has no table");
        } else {
            require(target.schema, "a " + target.type + " target needs a schema");
            forbid(target.digest, "a " + target.type + " target has no digest");
            if (TABLE.equals(target.type)) {
                require(target.table, "a table target needs a table");
            } else {
                forbid(target.table, "a schema target has no table");
            }
        }
    }

    /**
     * The legacy address, or null when this target has no path form.
     *
     * <p>Null rather than a best effort: a name containing {@code /} produces a
     * string that cannot be parsed back and is ambiguous with a different
     * target, so there is no honest path to return. The generated column in the
     * database follows the same rule, which is what keeps the two agreeing.
     */
    public String path() {
        if (QUERY.equals(type)) {
            return representable(digest) ? "/query/" + digest : null;
        }
        if (!representable(schema)) {
            return null;
        }
        if (SCHEMA.equals(type)) {
            return "/schema/" + schema;
        }
        return representable(table) ? "/schema/" + schema + "/table/" + table : null;
    }

    private static boolean representable(String value) {
        return value != null && !value.isEmpty() && value.indexOf('/') < 0;
    }

    private static String empty(String value) {
        return value == null ? "" : value;
    }

    private static void require(String value, String message) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(message);
        }
    }

    private static void forbid(String value, String message) {
        if (value != null && !value.isEmpty()) {
            throw new IllegalArgumentException(message);
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TagTarget target
            && Objects.equals(empty(type), empty(target.type))
            && Objects.equals(empty(schema), empty(target.schema))
            && Objects.equals(empty(table), empty(target.table))
            && Objects.equals(empty(digest), empty(target.digest));
    }

    @Override
    public int hashCode() {
        return Objects.hash(empty(type), empty(schema), empty(table), empty(digest));
    }
}
