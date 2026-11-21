package com.github.safetytrick.sqlexplorer.auth;

public record Scope(String environment, String datasource) implements java.io.Serializable {

    public static final Scope GLOBAL = new Scope(null, null);

    public Scope {
        if (environment == null && datasource != null) {
            throw new IllegalArgumentException("a datasource belongs to an environment");
        }
    }

    public static Scope environment(String name) {
        return new Scope(name, null);
    }

    public static Scope datasource(String environment, String datasource) {
        return new Scope(environment, datasource);
    }

    public static Scope parse(String text) {
        if (text == null || text.isBlank()) {
            return GLOBAL;
        }
        String[] parts = text.split("/", -1);
        if (parts.length > 2 || parts[0].isBlank() || (parts.length == 2 && parts[1].isBlank())) {
            return null;
        }
        return parts.length == 1 ? environment(parts[0]) : datasource(parts[0], parts[1]);
    }

    public boolean covers(Scope other) {
        if (environment == null) {
            return true;
        }
        if (!environment.equals(other.environment)) {
            return false;
        }
        return datasource == null || datasource.equals(other.datasource);
    }

    @Override
    public String toString() {
        return environment == null ? "*" : datasource == null ? environment : environment + "/" + datasource;
    }
}
