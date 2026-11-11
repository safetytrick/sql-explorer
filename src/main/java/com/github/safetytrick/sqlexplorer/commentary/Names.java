package com.github.safetytrick.sqlexplorer.commentary;

import java.util.regex.Pattern;

/**
 * The limits on the names people choose: tag names and environment names.
 *
 * <p>Both are capped at {@value #MAX_LENGTH} characters, and every column that
 * stores one is {@code varchar(48)} to match. Neither is free text: a tag name
 * is a chip, a URL parameter and part of a unique key; an environment name is
 * a header value and the leading column of every commentary key. A long one
 * fits nowhere it is shown, and each spare character is key length paid on
 * every index that leads with it.
 */
public final class Names {

    public static final int MAX_LENGTH = 48;

    /** Tag names are used in URLs and filenames; keep them boring. */
    private static final Pattern TAG_NAME = Pattern.compile("[\\w-]+");

    private Names() {
    }

    /** Why {@code name} cannot be a tag name, or null when it can. */
    public static String tagNameProblem(String name) {
        if (name == null || !TAG_NAME.matcher(name).matches()) {
            return "name must only contain letters, numbers, underscores and hyphens";
        }
        if (name.length() > MAX_LENGTH) {
            return "name is longer than " + MAX_LENGTH + " characters";
        }
        return null;
    }

    /** Why {@code name} cannot be an environment name, or null when it can. */
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
