package com.github.safetytrick.sqlexplorer.graph;

public record GraphLayoutItem(String kind, String schema, String name, Integer col, Integer row,
                              boolean override, String parent) {
}
