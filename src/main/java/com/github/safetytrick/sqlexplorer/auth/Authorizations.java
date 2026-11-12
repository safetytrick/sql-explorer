package com.github.safetytrick.sqlexplorer.auth;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

/**
 * What the current request may do (sqlx-e13.4): the grants its groups carry,
 * or everything when sign-in is off. A request made with an access token may
 * do what both its owner and the token's ceiling allow (sqlx-e13.5).
 *
 * <p>Enforcement is by URL, in {@link SecurityConfig}; this is for the places
 * that shape an answer to the caller rather than refuse it, such as listing
 * only the environments they can see.
 */
@Service
public class Authorizations {

    private final AuthProperties auth;
    private final GroupGrants groupGrants;

    public Authorizations(AuthProperties auth, GroupGrants groupGrants) {
        this.auth = auth;
        this.groupGrants = groupGrants;
    }

    public Grants current() {
        return of(SecurityContextHolder.getContext().getAuthentication());
    }

    public Grants of(Authentication authentication) {
        if (!auth.oidc()) {
            return Grants.EVERYTHING;
        }
        if (authentication instanceof AccessTokenAuthentication token) {
            // already min(owner's grants, the token's ceiling): sqlx-e13.5
            return token.grants();
        }
        if (authentication != null && authentication.getPrincipal() instanceof OidcUser user) {
            return groupGrants.of(UserService.groups(user));
        }
        return Grants.NOTHING;
    }
}
