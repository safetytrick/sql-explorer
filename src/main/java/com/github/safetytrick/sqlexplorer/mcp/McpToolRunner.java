package com.github.safetytrick.sqlexplorer.mcp;

import java.util.List;
import java.util.stream.Collectors;

import com.github.safetytrick.sqlexplorer.auth.Authorizations;
import com.github.safetytrick.sqlexplorer.auth.Grants;
import com.github.safetytrick.sqlexplorer.auth.Permission;
import com.github.safetytrick.sqlexplorer.auth.UserService;
import com.github.safetytrick.sqlexplorer.environment.Environment;
import com.github.safetytrick.sqlexplorer.environment.EnvironmentContext;
import com.github.safetytrick.sqlexplorer.environment.EnvironmentRegistry;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Component
class McpToolRunner {

    static final int MAX_RESULT_CHARS = 60_000;

    static final String REQUEST = HttpServletRequest.class.getName();

    private static final Logger log = LoggerFactory.getLogger(McpToolRunner.class);
    private static final Logger AUDIT = LoggerFactory.getLogger("sql-explorer.audit");

    private final JsonMapper jsonMapper;
    private final EnvironmentRegistry registry;
    private final Authorizations authorizations;
    private final UserService users;

    McpToolRunner(JsonMapper jsonMapper, EnvironmentRegistry registry, Authorizations authorizations, UserService users) {
        this.jsonMapper = jsonMapper;
        this.registry = registry;
        this.authorizations = authorizations;
        this.users = users;
    }

    static Permission permissionFor(McpTool tool) {
        return tool.writes() ? Permission.COMMENTARY_WRITE : Permission.EXPLORER_READ;
    }

    CallToolResult run(McpTool tool, McpTransportContext context, CallToolRequest call) {
        McpArgs args = new McpArgs(call.arguments(), jsonMapper);
        try {
            if (tool.environmentScoped()) {
                Grants grants = authorizations.current();
                Permission needed = permissionFor(tool);
                String environment = selectEnvironment(args.environment(), context, grants, needed);
                if (!grants.allows(needed, environment)) {
                    return text("Not allowed: " + tool.name() + " needs " + needed.key() + " on environment \""
                        + environment + "\", which " + (users.accessToken().isPresent()
                            ? "this access token does not have (its own ceiling, or its owner's grants, leave it out)"
                            : "you do not have") + ".", true);
                }
                if (tool.writes()) {
                    users.accessToken().ifPresent(token -> AUDIT.info("mcp tool {} on {} by {}",
                        tool.name(), environment, token.getPrincipal()));
                }
            }
            return text(render(tool.handler().apply(args)), false);
        } catch (ResponseStatusException e) {
            return text(e.getStatusCode().value() + " " + (e.getReason() != null ? e.getReason() : e.getMessage()), true);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return text(String.valueOf(e.getMessage()), true);
        } catch (RuntimeException e) {
            log.warn("MCP tool {} failed", tool.name(), e);
            return text(e.getClass().getSimpleName() + ": " + e.getMessage(), true);
        }
    }

    public static final String ENVIRONMENT_HEADER = "X-Sql-Explorer-Environment";

    private String selectEnvironment(String name, McpTransportContext context, Grants grants, Permission needed) {
        HttpServletRequest request = context.get(REQUEST) instanceof HttpServletRequest r ? r : null;
        if ((name == null || name.isBlank()) && request != null) {
            name = request.getHeader(ENVIRONMENT_HEADER);
        }
        if (name == null || name.isBlank()) {
            name = registry.getDefaultName();
            if (!grants.allows(needed, name)) {
                String fallback = name;
                name = registry.list().stream().map(Environment::getName).sorted()
                    .filter(env -> grants.allows(needed, env)).findFirst().orElse(fallback);
            }
        } else if (registry.get(name) == null) {
            String known = registry.list().stream().map(Environment::getName)
                .filter(env -> grants.allows(Permission.EXPLORER_READ, env))
                .sorted().collect(Collectors.joining(", "));
            throw new IllegalArgumentException("no environment \"" + name + "\"; known: " + known);
        }
        if (request != null) {
            request.setAttribute(EnvironmentContext.OVERRIDE_ATTRIBUTE, name);
        }
        return name;
    }

    private String render(Object result) {
        String text = result instanceof String string ? string : jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        if (text.length() > MAX_RESULT_CHARS) {
            return text.substring(0, MAX_RESULT_CHARS)
                + "\n… truncated (" + text.length() + " chars total — narrow the request)";
        }
        return text;
    }

    private static CallToolResult text(String text, boolean error) {
        return CallToolResult.builder().content(List.of(new io.modelcontextprotocol.spec.McpSchema.TextContent(text)))
            .isError(error).build();
    }
}
