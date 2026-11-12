package com.github.safetytrick.sqlexplorer.profiles;

import com.github.safetytrick.sqlexplorer.environment.EnvironmentScoped;
import java.util.List;

import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.profiles.CoQueriedService.CoQueriedTable;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Table")
@EnvironmentScoped
@RestController
class CoQueriedResource {

    private final CoQueriedService coQueriedService;

    CoQueriedResource(CoQueriedService coQueriedService) {
        this.coQueriedService = coQueriedService;
    }

    @GetMapping("/table/{schema}/{name}/co-queried")
    public List<CoQueriedTable> getCoQueried(@PathVariable String schema, @PathVariable String name) {
        return coQueriedService.getCoQueried(TableReference.newTableReference(schema, name));
    }
}
