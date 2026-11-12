package com.github.safetytrick.sqlexplorer.commentary;

import com.github.safetytrick.sqlexplorer.environment.EnvironmentScoped;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import com.github.safetytrick.sqlexplorer.model.SchemaRollup;
import com.github.safetytrick.sqlexplorer.model.TableRollup;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@io.swagger.v3.oas.annotations.tags.Tag(name="Tags")
@EnvironmentScoped
@RestController
class SqlTagsResource {

    private final SqlTagsService sqlTagsService;

    public SqlTagsResource(SqlTagsService sqlTagsService) {
        this.sqlTagsService = sqlTagsService;
    }

    @GetMapping("/tables/tag/{name}")
    public Stream<TableRollup> getTablesByTag(@PathVariable String name) {
        return sqlTagsService.getTablesByTag(name);
    }

    @GetMapping("/schemas/tag/{name}")
    public Stream<SchemaRollup> getSchemasByTag(@PathVariable String name) {
        return sqlTagsService.getSchemasByTag(name);
    }

    @GetMapping("/analyzed-sql/tag/{name}")
    public Stream<SqlProfile> getSqlByTag(@PathVariable String name) {
        return sqlTagsService.getSqlByTag(name);
    }
}
