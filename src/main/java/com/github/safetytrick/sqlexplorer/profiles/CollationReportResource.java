package com.github.safetytrick.sqlexplorer.profiles;

import com.github.safetytrick.sqlexplorer.profiles.CollationReportService.CollationReport;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Tables by column charset and collation, and joins whose sides differ (sqlx-n5j.5). */
@Tag(name = "Schema")
@RestController
class CollationReportResource {

    private final CollationReportService collationReportService;

    CollationReportResource(CollationReportService collationReportService) {
        this.collationReportService = collationReportService;
    }

    @GetMapping("/collations")
    public CollationReport getReport() {
        return collationReportService.getReport();
    }
}
