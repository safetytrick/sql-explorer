package com.github.safetytrick.sqlexplorer.schema;

import java.util.List;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.core.CrossSchemaTable;

public interface GraphService {

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
