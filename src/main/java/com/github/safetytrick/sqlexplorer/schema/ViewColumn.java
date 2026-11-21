package com.github.safetytrick.sqlexplorer.schema;

import java.util.List;

import com.github.safetytrick.sqlexplorer.avro.ColumnReference;

public record ViewColumn(String name, List<ColumnReference> sources, boolean computed) { }
