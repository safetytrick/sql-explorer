package com.github.safetytrick.sqlexplorer.graph;

import java.util.List;

import com.github.safetytrick.sqlexplorer.model.TableReference;

record TableRelationships(
    TableReference table, List<TableReference> foreignKeyTables, List<TableReference> joinTables, List<TableReference> relatedTables
) { }
