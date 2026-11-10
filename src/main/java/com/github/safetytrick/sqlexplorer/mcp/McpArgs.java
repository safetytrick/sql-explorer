package com.github.safetytrick.sqlexplorer.mcp;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.json.JsonMapper;

/** A tool call's arguments, as the client sent them. */
public final class McpArgs {

    static final String ENVIRONMENT = "environment";

    private final Map<String, Object> arguments;
    private final JsonMapper jsonMapper;

    McpArgs(Map<String, Object> arguments, JsonMapper jsonMapper) {
        this.arguments = arguments == null ? Map.of() : arguments;
        this.jsonMapper = jsonMapper;
    }

    /** A required string: missing or blank is the caller's mistake, said as such. */
    public String string(String name) {
        String value = optionalString(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing argument: " + name);
        }
        return value;
    }

    public String optionalString(String name) {
        Object value = arguments.get(name);
        return value == null ? null : value.toString();
    }

    public Integer optionalInteger(String name) {
        Object value = arguments.get(name);
        if (value == null) { return null; }
        if (value instanceof Number number) { return number.intValue(); }
        try {
            return Integer.valueOf(value.toString());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a whole number, not " + value);
        }
    }

    /** A boolean argument, or {@code fallback} when the caller left it out. */
    public boolean flag(String name, boolean fallback) {
        Object value = arguments.get(name);
        if (value == null) { return fallback; }
        return value instanceof Boolean flag ? flag : Boolean.parseBoolean(value.toString());
    }

    /**
     * Every argument but {@code environment} bound to {@code type}, as a JSON
     * request body would be: what a tool mirroring a POST endpoint passes on.
     * {@code defaults} fill in what the caller left out.
     */
    public <T> T as(Class<T> type, Map<String, Object> defaults) {
        Map<String, Object> body = new HashMap<>(defaults);
        arguments.forEach((name, value) -> { if (!ENVIRONMENT.equals(name)) { body.put(name, value); } });
        return jsonMapper.convertValue(body, type);
    }

    /** The streams resources return, made a list so they serialize like the REST response. */
    public static <T> List<T> listOf(java.util.stream.Stream<T> stream) {
        return stream.toList();
    }

    String environment() {
        return optionalString(ENVIRONMENT);
    }
}
