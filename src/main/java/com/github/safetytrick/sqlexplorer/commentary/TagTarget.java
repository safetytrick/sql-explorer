package com.github.safetytrick.sqlexplorer.commentary;

import java.util.Objects;

/**
 * Unused parts are empty, never null: MySQL treats NULLs as distinct in a
 * unique index, so the storage cannot use them.
 */
public record TagTarget(String type, String schema, String table, String digest, String column) {

    public static final String SCHEMA = "schema";
    public static final String TABLE = "table";
    public static final String COLUMN = "column";
    public static final String QUERY = "query";
    public static final String ENVIRONMENT = "environment";

    public TagTarget(String type, String schema, String table, String digest) {
        this(type, schema, table, digest, "");
    }

    public static TagTarget ofSchema(String schema) {
        return new TagTarget(SCHEMA, schema, "", "").normalized();
    }

    public static TagTarget ofTable(String schema, String table) {
        return new TagTarget(TABLE, schema, table, "").normalized();
    }

    public static TagTarget ofColumn(String schema, String table, String column) {
        return new TagTarget(COLUMN, schema, table, "", column).normalized();
    }

    public static TagTarget ofQuery(String digest) {
        return new TagTarget(QUERY, "", "", digest).normalized();
    }

    public static TagTarget ofEnvironment() {
        return new TagTarget(ENVIRONMENT, "", "", "", "");
    }

    public TagTarget normalized() {
        return new TagTarget(type, empty(schema), empty(table), empty(digest), empty(column));
    }

    public TagTarget validated() {
        validate();
        return normalized();
    }

    public TagTarget validatedForTags() {
        TagTarget target = validated();
        if (ENVIRONMENT.equals(target.type)) {
            throw new IllegalArgumentException(
                "a tag addresses a schema, table, column or query, not the environment");
        }
        return target;
    }

    public void validate() {
        TagTarget target = normalized();
        if (!SCHEMA.equals(target.type) && !TABLE.equals(target.type) && !COLUMN.equals(target.type)
            && !QUERY.equals(target.type) && !ENVIRONMENT.equals(target.type)) {
            throw new IllegalArgumentException(
                "type must be " + SCHEMA + ", " + TABLE + ", " + COLUMN + ", " + QUERY
                    + " or " + ENVIRONMENT + ", not " + target.type);
        }
        if (ENVIRONMENT.equals(target.type)) {
            forbid(target.schema, "an environment target has no schema");
            forbid(target.table, "an environment target has no table");
            forbid(target.column, "an environment target has no column");
            forbid(target.digest, "an environment target has no digest");
        } else if (QUERY.equals(target.type)) {
            require(target.digest, "a query target needs a digest");
            forbid(target.schema, "a query target has no schema");
            forbid(target.table, "a query target has no table");
            forbid(target.column, "a query target has no column");
        } else {
            require(target.schema, "a " + target.type + " target needs a schema");
            forbid(target.digest, "a " + target.type + " target has no digest");
            if (SCHEMA.equals(target.type)) {
                forbid(target.table, "a schema target has no table");
            } else {
                require(target.table, "a " + target.type + " target needs a table");
            }
            if (COLUMN.equals(target.type)) {
                require(target.column, "a column target needs a column");
            } else {
                forbid(target.column, "a " + target.type + " target has no column");
            }
        }
    }

    public String path() {
        if (ENVIRONMENT.equals(type)) {
            return null;
        }
        if (QUERY.equals(type)) {
            return representable(digest) ? "/query/" + digest : null;
        }
        if (!representable(schema)) {
            return null;
        }
        if (SCHEMA.equals(type)) {
            return "/schema/" + schema;
        }
        if (!representable(table)) {
            return null;
        }
        String tablePath = "/schema/" + schema + "/table/" + table;
        if (COLUMN.equals(type)) {
            return representable(column) ? tablePath + "/column/" + column : null;
        }
        return tablePath;
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
            && Objects.equals(empty(digest), empty(target.digest))
            && Objects.equals(empty(column), empty(target.column));
    }

    @Override
    public int hashCode() {
        return Objects.hash(empty(type), empty(schema), empty(table), empty(digest), empty(column));
    }
}
