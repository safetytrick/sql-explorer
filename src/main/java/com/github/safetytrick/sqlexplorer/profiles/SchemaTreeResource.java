package com.github.safetytrick.sqlexplorer.profiles;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Schema")
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
