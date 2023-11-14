package com.github.safetytrick.sqlexplorer.cleanup;

public class NameNormalizer {

    public static String normalize(String name) {
        if (name == null) {
            return null;
        }
        return name.replaceAll("`","").toLowerCase();
    }

}
