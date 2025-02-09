package com.github.safetytrick.sqlexplorer.graph;

import java.util.List;

import com.github.safetytrick.sqlexplorer.avro.TableReference;

record TableRelationships(
    TableReference table, List<TableReference> foreignKeyTables, List<TableReference> joinTables, List<TableReference> relatedTables
) { }
