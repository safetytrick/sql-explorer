package com.github.safetytrick.sqlexplorer.auth;

import java.util.List;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name="User")
@RestController
class UserResource {

    private final UserService userService;
    private final AuthProperties auth;

    UserResource(UserService userService, AuthProperties auth) {
        this.userService = userService;
        this.auth = auth;
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
     * @param groups  the {@code groups} claim, exactly as sent. Reported only:
     *                nothing maps it to permissions yet (sqlx-e13.4).
     */
    record CurrentUser(String mode, User user, String issuer, String subject, List<String> groups) {}

    @GetMapping("/user")
    public CurrentUser getCurrentUser() {
        return userService.signedInPrincipal()
            .map(oidc -> new CurrentUser(auth.mode().value(), userService.currentUser(),
                oidc.getIssuer() == null ? null : oidc.getIssuer().toExternalForm(),
                oidc.getSubject(), UserService.groups(oidc)))
            .orElseGet(() -> new CurrentUser(auth.mode().value(), userService.currentUser(), null, null, List.of()));
    }

}
