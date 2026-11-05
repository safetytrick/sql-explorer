package com.github.safetytrick.sqlexplorer.graph;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.CrudRepository;

interface GraphLayoutRepository extends CrudRepository<GraphLayoutEntry, Integer> {

    List<GraphLayoutEntry> findAllByOrderBySchemaNameAscKindAscItemNameAsc();

    Optional<GraphLayoutEntry> findByKindAndSchemaNameAndItemName(String kind, String schemaName, String itemName);

    long deleteBySchemaNameAndKindIn(String schemaName, Collection<String> kinds);
}
