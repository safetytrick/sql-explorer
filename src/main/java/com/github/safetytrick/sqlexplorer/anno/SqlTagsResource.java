package com.github.safetytrick.sqlexplorer.anno;

import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.core.AnalyzedSql;
import com.github.safetytrick.sqlexplorer.core.TableRollup;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SqlTagsResource {

    private final SqlTagsService sqlTagsService;

    public SqlTagsResource(SqlTagsService sqlTagsService) {
        this.sqlTagsService = sqlTagsService;
    }

    @GetMapping("/api/sql-explorer/tables/tag/{name}")
    public Stream<TableRollup> getTablesByTag(@PathVariable("name") String name) {
        return sqlTagsService.getTablesByTag(name);
    }

    @GetMapping("/api/sql-explorer/analyzed-sql/tag/{name}")
    public Stream<AnalyzedSql> getSqlByTag(@PathVariable("name") String name) {
        return sqlTagsService.getSqlByTag(name);
    }
}
