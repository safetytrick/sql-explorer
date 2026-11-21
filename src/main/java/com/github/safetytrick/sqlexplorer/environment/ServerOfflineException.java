package com.github.safetytrick.sqlexplorer.environment;

public class ServerOfflineException extends IllegalStateException {

    public ServerOfflineException(String environment, String server) {
        super("environment " + environment + " is served from its cache, so server " + server
            + " is not connected; pull the environment to go live");
    }
}
