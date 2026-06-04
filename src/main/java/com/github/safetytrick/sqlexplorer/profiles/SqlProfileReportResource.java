package com.github.safetytrick.sqlexplorer.profiles;

import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name="SqlProfile")
@RestController
class SqlProfileReportResource {

    private final SqlProfileReportService sqlProfileReportService;

    public SqlProfileReportResource(SqlProfileReportService sqlProfileReportService) {
        this.sqlProfileReportService = sqlProfileReportService;
    }

    @GetMapping("/analyzed-sql/reports/{name}")
    public Stream<SqlProfile> getNamedReport(@PathVariable String name,
                                             @RequestParam(name = "limit", defaultValue = "10") int limit,
                                             @RequestParam(name = "offset", defaultValue = "0") int offset) {
        return sqlProfileReportService.getNamedReport(name)
            .skip(offset)
            .limit(limit);
    }
}
