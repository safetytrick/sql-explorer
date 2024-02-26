package com.github.safetytrick.sqlexplorer.evidence.xstatementanalysis;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
class MySqlStatementAnalysisHost {
    private String jdbcUrl;
    private String username;
    private String password;
}
