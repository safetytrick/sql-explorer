package com.github.safetytrick.sqlexplorer.environment;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SourceDefinition {
    private String type;
    private String directory;
    private String pattern;
    private String dateFormat;
}
