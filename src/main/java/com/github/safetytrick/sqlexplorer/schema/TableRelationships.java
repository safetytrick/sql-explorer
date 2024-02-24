package com.github.safetytrick.sqlexplorer.schema;

import java.util.List;

import com.github.safetytrick.sqlexplorer.core.TableReference;

public record TableRelationships(
    TableReference table, List<TableReference> foreignKeys, List<TableReference> joinTables, List<TableReference> relatedTables
) { }
