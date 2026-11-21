package com.github.safetytrick.sqlexplorer.analysis;

import com.github.safetytrick.sqlexplorer.analysis.parser.QueryNormalizer;

public final class AnalyzerVersion {

    public static final int ANALYZERS = 9;

    public static final String CURRENT = "a" + ANALYZERS + ".n" + QueryNormalizer.VERSION;

    private AnalyzerVersion() {
    }
}
