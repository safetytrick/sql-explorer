package com.github.safetytrick.sqlexplorer.ddl;

import java.util.List;

import com.github.safetytrick.sqlexplorer.mcp.McpTool;
import com.github.safetytrick.sqlexplorer.mcp.McpToolProvider;
import org.springframework.stereotype.Component;

@Component
class DdlMcpTools implements McpToolProvider {

    private final DdlPreflightService service;

    DdlMcpTools(DdlPreflightService service) {
        this.service = service;
    }

    @Override
    public List<McpTool> tools() {
        return List.of(
            McpTool.named("ddl_preflight", "Advice on an ALTER TABLE, CREATE INDEX or DROP INDEX before it runs: "
                    + "the algorithm MySQL will use (INSTANT, INPLACE, INPLACE with a rebuild, COPY), whether writes "
                    + "can continue, row-size and index-key limits after the change, foreign keys and what they mean "
                    + "for pt-online-schema-change and gh-ost, unique indexes at risk from a collation change, "
                    + "latin1-to-UTF-8 double-encoding risk, table size, write rate and lock risk. Read-only: the "
                    + "statement is never executed.")
                .string("sql", "the ALTER TABLE, CREATE INDEX or DROP INDEX statement")
                .optionalInteger("longStatementSeconds",
                    "max time from which a captured statement counts as a lock risk; default 1", 1, 86_400)
                .handler(args -> {
                    Integer seconds = args.optionalInteger("longStatementSeconds");
                    return service.check(args.string("sql"),
                        (seconds == null ? DdlPreflightResource.DEFAULT_LONG_STATEMENT_SECONDS : seconds) * 1000d);
                })
        );
    }
}
