package com.github.safetytrick.sqlexplorer.auth;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import com.github.safetytrick.sqlexplorer.environment.Environment;
import com.github.safetytrick.sqlexplorer.environment.EnvironmentRegistry;

import java.util.List;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name="User")
@RestController
class UserResource {

    private final UserService userService;
    private final AuthProperties auth;

    private final Authorizations authorizations;
    private final EnvironmentRegistry registry;

    UserResource(UserService userService, AuthProperties auth, Authorizations authorizations, EnvironmentRegistry registry) {
        this.userService = userService;
        this.auth = auth;
        this.authorizations = authorizations;
        this.registry = registry;
    }

    /**
     * Who is signed in, and how.
     *
     * @param mode    {@code oidc} or {@code none}; with {@code none} the user is
     *                always the legacy {@code admin} and the UI says sign-in is
     *                not configured
     * @param user    the {@code users} row authorship is recorded against
     * @param issuer  the provider that signed them in; absent in {@code none}
     * @param subject their id at that provider; absent in {@code none}
     * @param groups  the {@code groups} claim, exactly as sent
     * @param grants  what those groups grant, as {@code role:scope}
     *                ({@code viewer:prod}, {@code admin:*}); everything when
     *                sign-in is off (sqlx-e13.4)
     * @param admin   whether the operator pages are open to them
     * @param environments per environment they can see, the environment-level
     *                permissions they hold there ({@code explorer:read},
     *                {@code env:explain}, …), so the UI can leave out what
     *                would only be refused
     * @param token   the access token the request was made with, if it was
     *                (sqlx-e13.5); then {@code grants} are what the token may
     *                do, its owner's narrowed by its ceiling, and
     *                {@code groups} are those of the owner's latest sign-in
     * @param anonymous true for a visitor who is not signed in, reading what
     *                {@code sql-explorer.auth.anonymous} grants (sqlx-2lc);
     *                then {@code user} is null and the UI offers Sign in
     */
    record CurrentUser(String mode, User user, String issuer, String subject, List<String> groups,
                       List<String> grants, boolean admin, Map<String, List<String>> environments,
                       TokenInfo token, boolean anonymous) {}

    record TokenInfo(Integer id, String name) {}

    @GetMapping("/user")
    public CurrentUser getCurrentUser() {
        Grants grants = authorizations.current();
        List<String> granted = grants.grants().stream().map(Grant::toString).toList();
        Map<String, List<String>> environments = new LinkedHashMap<>();
        for (Environment environment : registry.list()) {
            List<String> held = Arrays.stream(Permission.values())
                .filter(p -> p.level() == Permission.Level.ENVIRONMENT && grants.allows(p, environment.getName()))
                .map(Permission::key)
                .toList();
            if (!held.isEmpty()) {
                environments.put(environment.getName(), held);
            }
        }
        var token = userService.accessToken();
        if (token.isPresent()) {
            User owner = userService.currentUser();
            var holder = token.get().getPrincipal();
            return new CurrentUser(auth.mode().value(), owner, owner.getIssuer(), owner.getSubject(),
                owner.getGroupsSnapshot() == null ? List.of() : owner.getGroupsSnapshot(), granted, grants.admin(),
                environments, new TokenInfo(holder.tokenId(), holder.tokenName()), false);
        }
        return userService.signedInPrincipal()
            .map(oidc -> new CurrentUser(auth.mode().value(), userService.currentUser(),
                oidc.getIssuer() == null ? null : oidc.getIssuer().toExternalForm(),
                oidc.getSubject(), UserService.groups(oidc), granted, grants.admin(), environments, null, false))
            // sign-in on and none here: a visitor, who is nobody, not the legacy admin (sqlx-2lc)
            .orElseGet(() -> auth.oidc()
                ? new CurrentUser(auth.mode().value(), null, null, null, List.of(), granted, false, environments, null, true)
                : new CurrentUser(auth.mode().value(), userService.currentUser(), null, null, List.of(),
                    granted, grants.admin(), environments, null, false));
    }

}
