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

@Tag(name = "DDL")
@EnvironmentScoped
@RestController
class DdlPreflightResource {

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

    record PreflightRequest(String sql, Double longStatementSeconds) { }

    @PostMapping("/ddl/preflight")
    public Preflight preflight(@RequestBody PreflightRequest request) {
        double seconds = request.longStatementSeconds() == null || request.longStatementSeconds() <= 0
            ? DEFAULT_LONG_STATEMENT_SECONDS : request.longStatementSeconds();
        return service.check(request.sql(), seconds * 1000);
    }

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
