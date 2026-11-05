package com.github.safetytrick.sqlexplorer.graph;

/**
 * One item's place in the grid layout, as the client reads and writes it
 * (sqlx-aoy.15).
 *
 * @param kind     {@code schema}: a schema's cell in the environment grid;
 *                 {@code aggregate}: an aggregate's cell in its schema, named
 *                 by its root table; {@code table}: a table's cell in its
 *                 aggregate, or in its schema when it is in none
 * @param schema   the schema, lower-cased
 * @param name     the aggregate's root or the table, lower-cased; empty for a schema
 * @param col      the slot's column, or null when the item has no saved slot
 * @param row      the slot's row, null exactly when {@code col} is
 * @param override tables only: true when the aggregate this table belongs to
 *                 was chosen by hand rather than inferred
 * @param parent   with {@code override}: the table it belongs under, or null
 *                 for "a root of its own"
 */
public record GraphLayoutItem(String kind, String schema, String name, Integer col, Integer row,
                              boolean override, String parent) {
}
