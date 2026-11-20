package com.github.safetytrick.sqlexplorer.ddl;

import java.util.List;

import com.github.safetytrick.sqlexplorer.environment.EnvironmentContext;
import com.github.safetytrick.sqlexplorer.environment.EnvironmentScoped;
import com.github.safetytrick.sqlexplorer.ddl.DdlPreflightService.Preflight;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@code POST /ddl/preflight}: advice on an ALTER TABLE, CREATE INDEX or DROP
 * INDEX before it runs (sqlx-n5j.11). Read-only: the statement is parsed and
 * judged against the schema snapshot and captured statistics, never executed.
 *
 * <p>{@code POST /ddl/lock-holders}: who holds a metadata lock right now on
 * the tables a change would lock, read live from the servers the request names
 * and no others (sqlx-9gk). The page sends it only from a button press.
 */
@Tag(name = "DDL")
@EnvironmentScoped
@RestController
class DdlPreflightResource {

    /** From what max time a captured statement counts as long, when the request does not say. */
    static final double DEFAULT_LONG_STATEMENT_SECONDS = 1;

    private final DdlPreflightService service;
    private final LockHolderService lockHolders;
    private final EnvironmentContext environmentContext;

    DdlPreflightResource(DdlPreflightService service, LockHolderService lockHolders,
                         EnvironmentContext environmentContext) {
        this.service = service;
        this.lockHolders = lockHolders;
        this.environmentContext = environmentContext;
    }

    /** @param longStatementSeconds max time from which a captured statement counts as a lock risk */
    record PreflightRequest(String sql, Double longStatementSeconds) { }

    @PostMapping("/ddl/preflight")
    public Preflight preflight(@RequestBody PreflightRequest request) {
        double seconds = request.longStatementSeconds() == null || request.longStatementSeconds() <= 0
            ? DEFAULT_LONG_STATEMENT_SECONDS : request.longStatementSeconds();
        return service.check(request.sql(), seconds * 1000);
    }

    /**
     * @param tables  the tables the change locks: itself, and the relatives it locks exclusively
     * @param servers the servers to read, by name; no other server is connected to
     */
    record LockHoldersRequest(List<LockHolderService.TableRef> tables, List<String> servers) { }

    @PostMapping("/ddl/lock-holders")
    public LockHolderService.Report lockHolders(@RequestBody LockHoldersRequest request) {
        try {
            return lockHolders.read(environmentContext.current(), request.tables(), request.servers());
        } catch (LockHolderService.InvalidRequest e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
