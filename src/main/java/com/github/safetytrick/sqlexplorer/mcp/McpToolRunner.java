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

/**
 * Runs one tool call: selects its environment, calls the handler, and turns
 * what comes back — or what goes wrong — into a result the client shows.
 *
 * <p>It also authorizes the call (sqlx-e13.5): {@code /mcp} is one URL for
 * every environment and every tool, so the URL rules cannot, and a call the
 * caller's grants do not allow is a tool error naming the missing permission.
 *
 * <p>A failure is a tool result with {@code isError}, never a protocol error,
 * so the model reads why and can correct the call; the reasons a REST caller
 * would get in a 4xx are passed on as they are.
 */
@Component
class McpToolRunner {

    /** Results are cut at this many characters, so one call cannot flood a model's context. */
    static final int MAX_RESULT_CHARS = 60_000;

    /** Where the transport puts the servlet request, for the handler on the same thread. */
    static final String REQUEST = HttpServletRequest.class.getName();

    private static final Logger log = LoggerFactory.getLogger(McpToolRunner.class);
    /** The same audit log as BearerTokenFilter's: a token's writes, with its owner and id. */
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

    /**
     * What a tool needs on its environment (sqlx-e13.5): the same permission
     * its REST endpoint does. Reads need {@code explorer:read}; the tag writes
     * {@code commentary:write}. Tools that take no environment (the SQL
     * utilities) are pure functions of their arguments and need only the
     * authentication {@code /mcp} already asked for.
     */
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

    /**
     * The client's MCP config can pin an environment with this header; a
     * tool's {@code environment} argument wins over it.
     */
    public static final String ENVIRONMENT_HEADER = "X-Sql-Explorer-Environment";

    /**
     * The environment the call names, else the one the client's config pins
     * with {@link #ENVIRONMENT_HEADER}, else the default. Checked: an unknown
     * name is refused rather than answered from another environment.
     *
     * <p>{@code /mcp} is one servlet URL for every environment, so unlike the
     * REST API it does not carry the environment in its path (sqlx-e13.6).
     * That is the one exception, so authorization cannot be a URL rule here:
     * {@link #run} checks each call against the environment chosen.
     *
     * <p>With none named, the default is used if the caller may use it there,
     * else the first environment they may, as {@code /environments} picks a
     * default for the UI. Unknown names list only environments the caller can
     * see.
     */
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
