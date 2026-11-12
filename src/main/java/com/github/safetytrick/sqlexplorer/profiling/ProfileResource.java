package com.github.safetytrick.sqlexplorer.profiling;

import com.github.safetytrick.sqlexplorer.environment.EnvironmentScoped;
import java.util.List;
import java.util.function.Supplier;

import com.github.safetytrick.sqlexplorer.auth.UserService;
import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.environment.Environment;
import com.github.safetytrick.sqlexplorer.environment.EnvironmentContext;
import com.github.safetytrick.sqlexplorer.profiling.ProfileService.ProfileRequestPlan;
import com.github.safetytrick.sqlexplorer.profiling.ProfileService.RunDetail;
import com.github.safetytrick.sqlexplorer.profiling.ProfileService.RunSummary;
import com.github.safetytrick.sqlexplorer.schema.SqlSchemaService;
import com.github.safetytrick.sqlexplorer.schema.TableStructure;
import lombok.Data;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Column data profiles over HTTP (sqlx-n5j.3). Reading a plan connects to
 * nothing; a run starts only on a POST that says it was confirmed, and reads
 * only sampling servers.
 */
@io.swagger.v3.oas.annotations.tags.Tag(name = "Profiles")
@EnvironmentScoped
@RestController
class ProfileResource {

    private final ProfileService profiles;
    private final EnvironmentContext environments;
    private final SqlSchemaService schema;
    private final UserService users;

    ProfileResource(ProfileService profiles, EnvironmentContext environments, SqlSchemaService schema,
                    UserService users) {
        this.profiles = profiles;
        this.environments = environments;
        this.schema = schema;
        this.users = users;
    }

    /** What a run would do, before it does anything. */
    @GetMapping("/profile/table/{schemaName}/{tableName}/plan")
    public ProfileRequestPlan plan(@PathVariable String schemaName, @PathVariable String tableName,
                                   @RequestParam(required = false) String server,
                                   @RequestParam(required = false) List<String> columns,
                                   @RequestParam(required = false) String fullScan) {
        Environment environment = environments.current();
        return profiles.plan(environment, table(schemaName, tableName), server, columns, fullScan);
    }

    /** Starts a run. {@code confirmed} must be true: the plan was shown and agreed to. */
    @PostMapping("/profile/table/{schemaName}/{tableName}/runs")
    public RunSummary start(@PathVariable String schemaName, @PathVariable String tableName,
                            @RequestBody StartRequest request) {
        if (!request.isConfirmed()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "a run starts only once its plan has been shown and confirmed");
        }
        Environment environment = environments.current();
        TableStructure table = table(schemaName, tableName);
        ProfileRun run = badRequestOn(() -> profiles.start(environment, table, request.getServer(),
            request.getColumns(), request.getFullScan(), users.currentUser()));
        return profiles.run(run.getId()).orElseThrow().run();
    }

    /** Every run of the table in this environment, newest first. */
    @GetMapping("/profile/table/{schemaName}/{tableName}/runs")
    public List<RunSummary> runs(@PathVariable String schemaName, @PathVariable String tableName) {
        return profiles.runs(environments.current().getName(), schemaName, tableName);
    }

    @GetMapping("/profile/run/{id}")
    public RunDetail run(@PathVariable long id) {
        return profiles.run(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
            "no profile run " + id));
    }

    @PostMapping("/profile/run/{id}/cancel")
    public RunSummary cancel(@PathVariable long id) {
        profiles.cancel(id);
        return run(id).run();
    }

    private TableStructure table(String schemaName, String tableName) {
        TableStructure table = schema.getTable(TableReference.newTableReference(schemaName, tableName));
        if (table == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, schemaName + "." + tableName
                + " is not in this environment's schema");
        }
        return table;
    }

    private static <T> T badRequestOn(Supplier<T> call) {
        try {
            return call.get();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @Data
    public static class StartRequest {
        /** One server to probe; absent to sample every cluster. */
        private String server;
        /** The columns to profile; absent for every character column. */
        private List<String> columns;
        /** The one column to read in full; absent to sample. */
        private String fullScan;
        private boolean confirmed;
    }
}
