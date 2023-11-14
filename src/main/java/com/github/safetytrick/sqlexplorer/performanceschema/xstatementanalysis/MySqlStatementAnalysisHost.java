package com.github.safetytrick.sqlexplorer.performanceschema.xstatementanalysis;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MySqlStatementAnalysisHost {
    private String jdbcUrl;
    private String username;
    private String password;
}
