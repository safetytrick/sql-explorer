package com.github.safetytrick.sqlexplorer.environment;

import lombok.Getter;
import lombok.Setter;

/**
 * Config for one SQL source on a server
 * (under {@code environments.<env>.servers.<server>.sources}).
 *
 * <p>{@code type} selects the factory that builds the source. Type-specific
 * params: {@code x-statement-analysis} reads x$statement_analysis over the
 * server's own connection (no extra params); {@code performance-schema-files}
 * reads exported TSV files (directory/pattern/dateFormat).
 */
@Getter
@Setter
public class SourceDefinition {
    private String type;
    private String directory;
    private String pattern;
    private String dateFormat;
}
