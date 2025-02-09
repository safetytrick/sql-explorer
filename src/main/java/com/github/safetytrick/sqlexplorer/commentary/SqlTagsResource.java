package com.github.safetytrick.sqlexplorer.commentary;

import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.model.TableRollup;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SqlTagsResource {

    private final SqlTagsService sqlTagsService;

    public SqlTagsResource(SqlTagsService sqlTagsService) {
        this.sqlTagsService = sqlTagsService;
    }

    @GetMapping("/api/sql-explorer/tables/tag/{name}")
    public Stream<TableRollup> getTablesByTag(@PathVariable String name) {
        return sqlTagsService.getTablesByTag(name);
    }

    @GetMapping("/api/sql-explorer/analyzed-sql/tag/{name}")
    public Stream<SqlProfile> getSqlByTag(@PathVariable String name) {
        return sqlTagsService.getSqlByTag(name);
    }
}
