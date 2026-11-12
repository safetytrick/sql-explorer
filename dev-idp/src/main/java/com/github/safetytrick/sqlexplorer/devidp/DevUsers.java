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
 * <p>Group names follow sql-explorer's scheme (sqlx-e13.4):
 * {@code sqlx:<role>[:<scope>]}, the scope being an environment
 * ({@code local}) or one datasource in it ({@code local/app}); no scope means
 * everywhere. Roles nest (Viewer, Runner, Author, Admin) but scopes do not
 * reach upward: Runner on {@code local/app} does not make {@code local}
 * readable, so the runner and author users carry a Viewer grant as well.
 *
 * <p>The environments are the ones the local docker config defines,
 * {@code local} and {@code production}, so each user can be tried there.
 */
public final class DevUsers {

    public static final String LOCAL = "local";
    public static final String PRODUCTION = "production";
    /** A datasource name, for the datasource-scoped grants managed queries will use. */
    public static final String APP = "app";

    public static final String ADMIN = "sqlx:admin";

    public static String viewer(String scope) {
        return "sqlx:viewer:" + scope;
    }

    public static String runner(String scope) {
        return "sqlx:runner:" + scope;
    }

    public static String author(String scope) {
        return "sqlx:author:" + scope;
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
            // sees local only
            new DevUser("viewer", "Vera Viewer", List.of(viewer(LOCAL))),
            // runs saved queries on one datasource; no EXPLAIN, which is environment-wide
            new DevUser("runner", "Rene Runner", List.of(viewer(LOCAL), runner(LOCAL + "/" + APP))),
            new DevUser("author", "Ada Author", List.of(viewer(LOCAL), author(LOCAL + "/" + APP))),
            // both environments, Runner across local (so EXPLAIN there), Author on one production datasource
            new DevUser("mixed", "Max Mixed", List.of(viewer(LOCAL), viewer(PRODUCTION), runner(LOCAL),
                    author(PRODUCTION + "/" + APP))),
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
