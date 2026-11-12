package com.github.safetytrick.sqlexplorer.auth;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * <p>A request made with a personal access token (sqlx-e13.5) is its owner:
 * what a token writes is authored by the person who made it, and the token's
 * id goes to the audit log beside it ({@link BearerTokenFilter}).
 *
 * <p>Everything else is the legacy {@code admin} user: every request when
 * sign-in is off, and, with it on, work no person started. That is startup
 * and scheduled pulls and inferred annotations. Rows authored before sign-in
 * stay attributed to {@code admin} too (sqlx-e13.2, decision 6).
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
        Optional<AccessTokenAuthentication> token = accessToken();
        if (token.isPresent()) {
            return userRepository.findById(token.get().getPrincipal().userId()).orElseThrow();
        }
        return signedInPrincipal().map(this::find).orElseGet(this::legacyAdmin);
    }

    /** The OIDC user this request was signed in as, if any: a browser session, not a token. */
    public Optional<OidcUser> signedInPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof OidcUser oidc) {
            return Optional.of(oidc);
        }
        return Optional.empty();
    }

    /** The personal access token this request was made with, if it was (sqlx-e13.5). */
    public Optional<AccessTokenAuthentication> accessToken() {
        return SecurityContextHolder.getContext().getAuthentication() instanceof AccessTokenAuthentication token
            ? Optional.of(token) : Optional.empty();
    }

    /** The groups claim, reported as sent. */
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
     *
     * <p>The groups claim is kept too, as the snapshot access tokens are
     * evaluated against (sqlx-e13.5), but only from an ID token issued no
     * earlier than the one the snapshot came from: a session that predates a
     * newer sign-in elsewhere must not put back groups the provider has since
     * taken away.
     */
    public User signedIn(OidcUser user) {
        String issuer = issuer(user);
        String subject = Objects.requireNonNull(user.getSubject(), "an ID token has a subject");
        Instant issuedAt = user.getIssuedAt() != null ? user.getIssuedAt() : Instant.now();
        return signedIn(issuer, subject, user.getEmail(), user.getFullName(), groups(user), issuedAt);
    }

    /** A sign-in that leaves the groups snapshot as it is. */
    User signedIn(String issuer, String subject, String email, String name) {
        return signedIn(issuer, subject, email, name, null, null);
    }

    /**
     * A sign-in whose ID token, issued at {@code issuedAt}, carried
     * {@code groups}. Null groups leave the snapshot alone.
     */
    User signedIn(String issuer, String subject, String email, String name, List<String> groups, Instant issuedAt) {
        User row = userRepository.findByIssuerAndSubject(issuer, subject).orElseGet(User::new);
        Instant seenOn = issuedAt == null ? null : issuedAt.truncatedTo(ChronoUnit.MILLIS);
        boolean newerGroups = groups != null && seenOn != null
            && (row.getGroupsSeenOn() == null || !seenOn.isBefore(row.getGroupsSeenOn()))
            && !(Objects.equals(row.getGroupsSnapshot(), groups) && seenOn.equals(row.getGroupsSeenOn()));
        boolean changed = row.getId() == null || newerGroups
            || !Objects.equals(row.getEmail(), email) || !Objects.equals(row.getName(), name);
        if (!changed) {
            return row;
        }
        row.setIssuer(issuer);
        row.setSubject(subject);
        row.setEmail(email);
        row.setName(name);
        if (newerGroups) {
            row.setGroupsSnapshot(List.copyOf(groups));
            row.setGroupsSeenOn(seenOn);
        }
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
