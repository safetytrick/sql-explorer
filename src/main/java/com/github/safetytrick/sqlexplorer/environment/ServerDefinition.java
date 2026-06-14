package com.github.safetytrick.sqlexplorer.environment;

import lombok.Getter;
import lombok.Setter;

/**
 * Config for one database server within an environment
 * (under {@code environments.<env>.servers.<name>}).
 */
@Getter
@Setter
public class ServerDefinition {
    private String jdbcUrl;
    private String username;
    private String password;
}
