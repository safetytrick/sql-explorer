package com.github.safetytrick.sqlexplorer.profiling;

import java.util.List;

public record ProfileResult(List<ServerResult> servers, List<ColumnResult> columns, String confidence) {

    public record ServerResult(String cluster, String server, Long rowsEstimate, String status, String reason,
                               boolean complete, String coverage, List<WindowResult> windows, long rowsRead,
                               int statementCount, List<String> statements, List<ColumnResult> columns) { }

    public record WindowResult(String label, String from, String to, long rows, long millis, String error) { }

    public record ColumnResult(String column, String type, String characterSet, String collation, long rows,
                               long nulls, long nonAscii, Long maxBytes, long distinctAtLeast,
                               List<SignatureResult> signatures, String verdict) { }

    public record SignatureResult(Signature signature, String label, String explanation, boolean heuristic,
                                  boolean informational, long rows, List<ServerIds> ids,
                                  List<CharacterCount> characters) { }

    public record ServerIds(String server, List<String> ids) { }

    public record CharacterCount(String matched, String codePoints, String intended, long rows) { }
}
