package com.github.safetytrick.sqlexplorer.auth;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

/**
 * Who is acting: the author stamped on tags, notes, annotations, sources and
 * the rest.
 *
 * <p>With sign-in on ({@code sql-explorer.auth.mode: oidc}) that is the
 * signed-in user, a {@code users} row keyed by issuer + subject, created at
 * first sign-in and refreshed at each one after (sqlx-e13.1).
 *
 * <p>Everything else is the legacy {@code admin} user: every request when
 * sign-in is off, and, with it on, work no person started. That is startup
 * and scheduled pulls, inferred annotations, and the MCP endpoint, which takes
 * no session until personal access tokens exist (sqlx-e13.5). Rows authored
 * before sign-in stay attributed to {@code admin} too (sqlx-e13.2, decision 6).
 */
@Service
public class UserService {

    /** The legacy user's email, which is its only identity. */
    public static final String LEGACY_ADMIN = "admin";

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User currentUser() {
        return signedInPrincipal().map(this::find).orElseGet(this::legacyAdmin);
    }

    /** The OIDC user this request was signed in as, if any. */
    public Optional<OidcUser> signedInPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof OidcUser oidc) {
            return Optional.of(oidc);
        }
        return Optional.empty();
    }

    /** The groups claim, reported as sent. Mapping them to roles is sqlx-e13.4. */
    public static List<String> groups(OidcUser user) {
        List<String> groups = user.getClaimAsStringList("groups");
        return groups == null ? List.of() : List.copyOf(groups);
    }

    public User legacyAdmin() {
        return userRepository.findFirstByIssuerIsNullAndEmailOrderByIdAsc(LEGACY_ADMIN).orElseThrow();
    }

    /** The row for this principal, created if it is missing (a session that predates its row, say). */
    private User find(OidcUser user) {
        return userRepository.findByIssuerAndSubject(issuer(user), user.getSubject())
            .orElseGet(() -> signedIn(user));
    }

    /**
     * Creates or updates the row for a sign-in, keyed by issuer + subject.
     * Email and name are copied from the claims each time, so a change at the
     * provider shows at the next sign-in.
     */
    public User signedIn(OidcUser user) {
        String issuer = issuer(user);
        String subject = Objects.requireNonNull(user.getSubject(), "an ID token has a subject");
        return signedIn(issuer, subject, user.getEmail(), user.getFullName());
    }

    User signedIn(String issuer, String subject, String email, String name) {
        User row = userRepository.findByIssuerAndSubject(issuer, subject).orElseGet(User::new);
        boolean changed = row.getId() == null
            || !Objects.equals(row.getEmail(), email) || !Objects.equals(row.getName(), name);
        if (!changed) {
            return row;
        }
        row.setIssuer(issuer);
        row.setSubject(subject);
        row.setEmail(email);
        row.setName(name);
        try {
            return userRepository.save(row);
        } catch (DataIntegrityViolationException raced) {
            // two first requests from one new user: the other one inserted the row
            return userRepository.findByIssuerAndSubject(issuer, subject).orElseThrow(() -> raced);
        }
    }

    private static String issuer(OidcUser user) {
        return Objects.requireNonNull(user.getIssuer(), "an ID token names its issuer").toExternalForm();
    }
}
