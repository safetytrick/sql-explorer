package com.github.safetytrick.sqlexplorer.environment;

public class SchemaPendingException extends IllegalStateException {

    public SchemaPendingException(String server, String why) {
        super("the schema of server " + server + " is not read yet because " + why
            + "; the snapshot is served meanwhile");
    }
}
