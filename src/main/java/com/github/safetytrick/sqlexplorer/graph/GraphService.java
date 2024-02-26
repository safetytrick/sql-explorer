package com.github.safetytrick.sqlexplorer.graph;

import java.util.List;
import java.util.stream.Stream;

interface GraphService {

    Stream<TableRelationships> getTableRelationships(int threshold);

    Stream<TableRelationships> getTableRelationshipsForSchema(int threshold, String schema);

    Stream<TableRelationships> getTableRelationshipsForSchemaAndTable(int threshold,
                                                                      int depth,
                                                                      String schema,
                                                                      String tableName);

    Stream<GroupedTableRelationships> getTableRelationshipsForTag(int threshold, int depth, String tag);

    List<CrossSchemaTable> getInfoForCrossSchemaJoins(String schema,
                                                      String tag,
                                                      String otherSchema,
                                                      int graphServiceThreshold,
                                                      int graphServiceDepth,
                                                      int threshold);
}
