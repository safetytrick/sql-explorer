package com.github.safetytrick.sqlexplorer.profiles;

import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.avro.SqlProfile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SqlProfileReportResource {

    private final SqlProfileReportService sqlProfileReportService;

    public SqlProfileReportResource(SqlProfileReportService sqlProfileReportService) {
        this.sqlProfileReportService = sqlProfileReportService;
    }

    @GetMapping("/api/sql-explorer/analyzed-sql/reports/{name}")
    public Stream<SqlProfile> getNamedReport(@PathVariable String name,
                                             @RequestParam(name = "limit", defaultValue = "10") int limit) {
        return sqlProfileReportService.getNamedReport(name).limit(limit);
    }
}
