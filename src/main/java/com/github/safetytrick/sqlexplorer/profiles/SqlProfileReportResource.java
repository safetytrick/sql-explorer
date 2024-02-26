package com.github.safetytrick.sqlexplorer.profiles;

import javax.ws.rs.DefaultValue;
import javax.ws.rs.QueryParam;
import java.util.stream.Stream;

import com.github.safetytrick.sqlexplorer.model.SqlProfile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SqlProfileReportResource {

    private final SqlProfileReportService sqlProfileReportService;

    public SqlProfileReportResource(SqlProfileReportService sqlProfileReportService) {
        this.sqlProfileReportService = sqlProfileReportService;
    }

    @GetMapping("/api/sql-explorer/analyzed-sql/reports/{name}")
    public Stream<SqlProfile> getNamedReport(@PathVariable("name") String name,
                                             @QueryParam("limit") @DefaultValue("10") Integer limit) {
        if (limit == null) {
            limit = 10; // not sure why default isn't working, wrong annotation?
        }
        return sqlProfileReportService.getNamedReport(name).limit(limit);
    }
}
