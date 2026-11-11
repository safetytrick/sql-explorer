package com.github.safetytrick.sqlexplorer.profiles;

import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.profiles.TableWorkloadService.LockRisk;
import com.github.safetytrick.sqlexplorer.profiles.TableWorkloadService.WriteRate;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Write rate and lock risk for one table (sqlx-n5j.9, sqlx-n5j.10). Zeros, not 404, for an unknown table. */
@Tag(name = "Table")
@RestController
class TableWorkloadResource {

    private final TableWorkloadService tableWorkloadService;

    TableWorkloadResource(TableWorkloadService tableWorkloadService) {
        this.tableWorkloadService = tableWorkloadService;
    }

    @GetMapping("/table/{schema}/{name}/write-rate")
    public WriteRate getWriteRate(@PathVariable String schema, @PathVariable String name) {
        return tableWorkloadService.getWriteRate(TableReference.newTableReference(schema, name));
    }

    @GetMapping("/table/{schema}/{name}/lock-risk")
    public LockRisk getLockRisk(@PathVariable String schema, @PathVariable String name) {
        return tableWorkloadService.getLockRisk(TableReference.newTableReference(schema, name));
    }
}
