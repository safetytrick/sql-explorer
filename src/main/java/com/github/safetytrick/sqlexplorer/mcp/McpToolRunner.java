package com.github.safetytrick.sqlexplorer.mcp;

import java.util.List;
import java.util.stream.Collectors;

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

    private final JsonMapper jsonMapper;
    private final EnvironmentRegistry registry;

    McpToolRunner(JsonMapper jsonMapper, EnvironmentRegistry registry) {
        this.jsonMapper = jsonMapper;
        this.registry = registry;
    }

    CallToolResult run(McpTool tool, McpTransportContext context, CallToolRequest call) {
        McpArgs args = new McpArgs(call.arguments(), jsonMapper);
        try {
            if (tool.environmentScoped()) { selectEnvironment(args.environment(), context); }
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
     * An environment the call names, checked: the REST API falls back to the
     * default for a name it does not know, which would answer a model's typo
     * with another environment's data and no sign of it.
     */
    private void selectEnvironment(String name, McpTransportContext context) {
        if (name == null || name.isBlank()) { return; }
        if (registry.get(name) == null) {
            String known = registry.list().stream().map(Environment::getName).sorted().collect(Collectors.joining(", "));
            throw new IllegalArgumentException("no environment \"" + name + "\"; known: " + known);
        }
        if (context.get(REQUEST) instanceof HttpServletRequest request) {
            request.setAttribute(EnvironmentContext.OVERRIDE_ATTRIBUTE, name);
        }
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
