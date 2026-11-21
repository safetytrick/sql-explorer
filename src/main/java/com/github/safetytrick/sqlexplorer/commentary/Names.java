package com.github.safetytrick.sqlexplorer.commentary;

import java.util.regex.Pattern;

public final class Names {

    public static final int MAX_LENGTH = 48;

    private static final Pattern TAG_NAME = Pattern.compile("[\\w-]+");

    private Names() {
    }

    public static String tagNameProblem(String name) {
        if (name == null || !TAG_NAME.matcher(name).matches()) {
            return "name must only contain letters, numbers, underscores and hyphens";
        }
        if (name.length() > MAX_LENGTH) {
            return "name is longer than " + MAX_LENGTH + " characters";
        }
        return null;
    }

    public static String environmentNameProblem(String name) {
        if (name == null || name.isBlank()) {
            return "an environment needs a name";
        }
        if (name.length() > MAX_LENGTH) {
            return "environment name " + name + " is longer than " + MAX_LENGTH + " characters";
        }
        return null;
    }
}
