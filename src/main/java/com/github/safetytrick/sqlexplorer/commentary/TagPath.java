package com.github.safetytrick.sqlexplorer.commentary;

import java.util.LinkedHashMap;
import java.util.Map;

record TagPath(Target target, String schema, String table, String column, String digest) {

    enum Target {
        SCHEMA("schema"), TABLE("table"), COLUMN("column"), QUERY("query");

        private final String type;

        Target(String type) {
            this.type = type;
        }

        String type() {
            return type;
        }
    }

    private static final String SCHEMA_PREFIX = "/schema/";
    private static final String TABLE_INFIX = "/table/";
    private static final String COLUMN_INFIX = "/column/";
    private static final String QUERY_PREFIX = "/query/";

    static TagPath parse(String path) {
        if (path == null) {
            return null;
        }
        if (path.startsWith(QUERY_PREFIX)) {
            String digest = path.substring(QUERY_PREFIX.length());
            return segment(digest) ? new TagPath(Target.QUERY, null, null, null, digest) : null;
        }
        if (!path.startsWith(SCHEMA_PREFIX)) {
            return null;
        }
        String rest = path.substring(SCHEMA_PREFIX.length());
        int infix = rest.indexOf(TABLE_INFIX);
        if (infix < 0) {
            return segment(rest) ? new TagPath(Target.SCHEMA, rest, null, null, null) : null;
        }
        String schema = rest.substring(0, infix);
        String table = rest.substring(infix + TABLE_INFIX.length());
        int columnInfix = table.indexOf(COLUMN_INFIX);
        if (columnInfix >= 0) {
            String column = table.substring(columnInfix + COLUMN_INFIX.length());
            table = table.substring(0, columnInfix);
            return segment(schema) && segment(table) && segment(column)
                ? new TagPath(Target.COLUMN, schema, table, column, null)
                : null;
        }
        return segment(schema) && segment(table)
            ? new TagPath(Target.TABLE, schema, table, null, null)
            : null;
    }

    static TagPath require(String path) {
        TagPath parsed = parse(path);
        if (parsed == null) {
            throw new IllegalArgumentException(
                "not a tag path: " + path + " — expected /schema/{schema}, "
                    + "/schema/{schema}/table/{table}, /schema/{schema}/table/{table}/column/{column} "
                    + "or /query/{digest}, "
                    + "with no '/' inside a name");
        }
        return parsed;
    }

    private static boolean segment(String value) {
        return !value.isEmpty() && value.indexOf('/') < 0;
    }

    String type() {
        return target.type();
    }

    TagTarget toTarget() {
        return new TagTarget(target.type(), schema, table, digest, column).normalized();
    }

    Map<String, String> elements() {
        Map<String, String> elements = new LinkedHashMap<>();
        if (schema != null) {
            elements.put(Target.SCHEMA.type(), schema);
        }
        if (table != null) {
            elements.put(Target.TABLE.type(), table);
        }
        if (column != null) {
            elements.put(Target.COLUMN.type(), column);
        }
        if (digest != null) {
            elements.put(Target.QUERY.type(), digest);
        }
        return elements;
    }
}
