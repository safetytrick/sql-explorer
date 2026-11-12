package com.github.safetytrick.sqlexplorer.devidp;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The canned logins. Each password is the username: these exist so a
 * developer or an e2e test can sign in as a given role without thinking.
 *
 * <p>Group names follow one scheme, which sqlx-e13.4 maps to roles:
 *
 * <pre>
 *   sqlx:viewer                 Viewer, everywhere
 *   sqlx:admin                  Admin, everywhere
 *   sqlx:runner:&lt;datasource&gt;    Runner (run saved queries) on one datasource
 *   sqlx:author:&lt;datasource&gt;    Author (write and edit SQL) on one datasource
 * </pre>
 *
 * A datasource name may contain letters, digits, '-' and '_' but never ':'.
 * Each user carries exactly the groups listed here and nothing implied: the
 * runner user is not also given {@code sqlx:viewer}, so whether one role
 * implies another is decided by the mapping, not by the IdP.
 */
public final class DevUsers {

    public static final String STAGING = "app-staging";
    public static final String PROD = "app-prod";

    public static final String VIEWER = "sqlx:viewer";
    public static final String ADMIN = "sqlx:admin";

    public static String runner(String datasource) {
        return "sqlx:runner:" + datasource;
    }

    public static String author(String datasource) {
        return "sqlx:author:" + datasource;
    }

    /** One canned login. The password is the username. */
    public record DevUser(String username, String name, List<String> groups) {
        public String password() {
            return username;
        }

        public String email() {
            return username + "@example.com";
        }
    }

    public static final List<DevUser> ALL = List.of(
            new DevUser("viewer", "Vera Viewer", List.of(VIEWER)),
            new DevUser("runner", "Rene Runner", List.of(runner(STAGING))),
            new DevUser("author", "Ada Author", List.of(author(STAGING))),
            new DevUser("mixed", "Max Mixed", List.of(runner(STAGING), author(PROD))),
            new DevUser("admin", "Alex Admin", List.of(ADMIN)),
            new DevUser("nogroups", "Nora Nogroups", List.of()));

    private static final Map<String, DevUser> BY_USERNAME =
            ALL.stream().collect(Collectors.toUnmodifiableMap(DevUser::username, Function.identity()));

    public static Optional<DevUser> find(String username) {
        return Optional.ofNullable(BY_USERNAME.get(username));
    }

    private DevUsers() {
    }
}
