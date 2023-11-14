package com.github.safetytrick.sqlexplorer.reporting;

import java.util.stream.Stream;

import javax.ws.rs.DefaultValue;
import javax.ws.rs.QueryParam;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.github.safetytrick.sqlexplorer.core.AnalyzedSql;

@RestController
public class SqlReportResource {

    private final SqlReportService sqlReportService;

    public SqlReportResource(SqlReportService sqlReportService) {
        this.sqlReportService = sqlReportService;
    }

    @GetMapping("/api/sql-explorer/analyzed-sql/reports/{name}")
    public Stream<AnalyzedSql> getNamedReport(@PathVariable("name") String name,
                                              @QueryParam("limit") @DefaultValue("10") Integer limit) {
        if (limit == null) {
            limit = 10; // not sure why default isn't working, wrong annotation?
        }
        return sqlReportService.getNamedReport(name).limit(limit);
    }
}
