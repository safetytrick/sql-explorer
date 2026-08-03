package com.github.safetytrick.sqlexplorer.schema;

import java.util.List;

import com.github.safetytrick.sqlexplorer.avro.ColumnReference;

/**
 * One output column of a view and the base-table columns it derives from.
 * {@code sources} is empty when the column couldn't be resolved (ambiguous
 * across joined tables, unknown table, or a construct the resolver doesn't
 * model); {@code computed} marks columns produced by an expression or
 * function rather than a bare column reference.
 */
public record ViewColumn(String name, List<ColumnReference> sources, boolean computed) { }
