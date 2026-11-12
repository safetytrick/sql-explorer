package com.github.safetytrick.sqlexplorer.ddl;

import com.github.safetytrick.sqlexplorer.environment.EnvironmentScoped;
import com.github.safetytrick.sqlexplorer.ddl.DdlPreflightService.Preflight;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /ddl/preflight}: advice on an ALTER TABLE, CREATE INDEX or DROP
 * INDEX before it runs (sqlx-n5j.11). Read-only: the statement is parsed and
 * judged against the schema snapshot and captured statistics, never executed.
 */
@Tag(name = "DDL")
@EnvironmentScoped
@RestController
class DdlPreflightResource {

    /** From what max time a captured statement counts as long, when the request does not say. */
    static final double DEFAULT_LONG_STATEMENT_SECONDS = 1;

    private final DdlPreflightService service;

    DdlPreflightResource(DdlPreflightService service) {
        this.service = service;
    }

    /** @param longStatementSeconds max time from which a captured statement counts as a lock risk */
    record PreflightRequest(String sql, Double longStatementSeconds) { }

    @PostMapping("/ddl/preflight")
    public Preflight preflight(@RequestBody PreflightRequest request) {
        double seconds = request.longStatementSeconds() == null || request.longStatementSeconds() <= 0
            ? DEFAULT_LONG_STATEMENT_SECONDS : request.longStatementSeconds();
        return service.check(request.sql(), seconds * 1000);
    }
}
