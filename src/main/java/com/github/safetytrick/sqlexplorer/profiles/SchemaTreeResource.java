package com.github.safetytrick.sqlexplorer.profiles;

import com.github.safetytrick.sqlexplorer.environment.EnvironmentScoped;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Schema")
@EnvironmentScoped
@RestController
class SchemaTreeResource {

    private final SchemaTreeService schemaTreeService;

    SchemaTreeResource(SchemaTreeService schemaTreeService) {
        this.schemaTreeService = schemaTreeService;
    }

    @GetMapping("/schema-tree")
    public SchemaTree getSchemaTree() {
        return schemaTreeService.getTree();
    }
}
