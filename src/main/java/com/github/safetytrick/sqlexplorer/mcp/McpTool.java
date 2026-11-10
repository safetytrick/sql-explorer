package com.github.safetytrick.sqlexplorer.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * One tool of the MCP endpoint ({@code /api/sql-explorer/mcp}): its name and
 * description as a client sees them, the arguments it takes, and what it does.
 *
 * <p>Tools are contributed by the domain packages through
 * {@link McpToolProvider}, next to the resources they call, so a tool is the
 * same operation as the REST endpoint beside it rather than a second copy of
 * it. Unless {@link Builder#environmentFree()} says otherwise every tool also
 * takes an optional {@code environment}, which {@link McpToolRunner} applies
 * before the handler runs.
 */
public record McpTool(String name, String description, Map<String, Object> inputSchema,
                      boolean environmentScoped, boolean writes, Function<McpArgs, Object> handler) {

    public McpTool {
        Objects.requireNonNull(name);
        Objects.requireNonNull(description);
        Objects.requireNonNull(handler);
    }

    public static Builder named(String name, String description) {
        return new Builder(name, description);
    }

    public static final class Builder {
        private final String name;
        private final String description;
        private final Map<String, Object> properties = new LinkedHashMap<>();
        private final List<String> required = new ArrayList<>();
        private boolean environmentScoped = true;
        private boolean writes;

        private Builder(String name, String description) {
            this.name = name;
            this.description = description;
        }

        /** A string argument the caller must give. */
        public Builder string(String argument, String description) {
            required.add(argument);
            return optionalString(argument, description);
        }

        public Builder optionalString(String argument, String description) {
            return property(argument, "string", description);
        }

        /** A string the caller must give, matching {@code pattern}. */
        public Builder string(String argument, String description, String pattern) {
            required.add(argument);
            Map<String, Object> schema = schema("string", description);
            schema.put("pattern", pattern);
            properties.put(argument, schema);
            return this;
        }

        public Builder optionalStrings(String argument, String description) {
            Map<String, Object> schema = schema("array", description);
            schema.put("items", Map.of("type", "string"));
            properties.put(argument, schema);
            return this;
        }

        public Builder optionalEnum(String argument, String description, List<String> values) {
            Map<String, Object> schema = schema("string", description);
            schema.put("enum", values);
            properties.put(argument, schema);
            return this;
        }

        public Builder requiredEnum(String argument, String description, List<String> values) {
            required.add(argument);
            return optionalEnum(argument, description, values);
        }

        public Builder optionalInteger(String argument, String description, int minimum, int maximum) {
            Map<String, Object> schema = schema("integer", description);
            schema.put("minimum", minimum);
            schema.put("maximum", maximum);
            properties.put(argument, schema);
            return this;
        }

        public Builder optionalBoolean(String argument, String description) {
            return property(argument, "boolean", description);
        }

        /** Takes no {@code environment}: the tool reads nothing an environment holds. */
        public Builder environmentFree() {
            this.environmentScoped = false;
            return this;
        }

        /** Changes something (only tags do), so a client can ask before calling it. */
        public Builder writes() {
            this.writes = true;
            return this;
        }

        public McpTool handler(Function<McpArgs, Object> handler) {
            Map<String, Object> props = new LinkedHashMap<>(properties);
            if (environmentScoped) {
                props.put(McpArgs.ENVIRONMENT, schema("string",
                    "SQL Explorer environment name; defaults to the X-Sql-Explorer-Environment header, then the server default"));
            }
            Map<String, Object> inputSchema = new LinkedHashMap<>();
            inputSchema.put("type", "object");
            inputSchema.put("properties", props);
            if (!required.isEmpty()) { inputSchema.put("required", List.copyOf(required)); }
            return new McpTool(name, description, inputSchema, environmentScoped, writes, handler);
        }

        private Builder property(String argument, String type, String description) {
            properties.put(argument, schema(type, description));
            return this;
        }

        private static Map<String, Object> schema(String type, String description) {
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", type);
            if (description != null) { schema.put("description", description); }
            return schema;
        }
    }
}
