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

@Service
public class UserService {

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

    public Optional<OidcUser> signedInPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof OidcUser oidc) {
            return Optional.of(oidc);
        }
        return Optional.empty();
    }

    public Optional<AccessTokenAuthentication> accessToken() {
        return SecurityContextHolder.getContext().getAuthentication() instanceof AccessTokenAuthentication token
            ? Optional.of(token) : Optional.empty();
    }

    public static List<String> groups(OidcUser user) {
        List<String> groups = user.getClaimAsStringList("groups");
        return groups == null ? List.of() : List.copyOf(groups);
    }

    public User legacyAdmin() {
        return userRepository.findFirstByIssuerIsNullAndEmailOrderByIdAsc(LEGACY_ADMIN).orElseThrow();
    }

    private User find(OidcUser user) {
        return userRepository.findByIssuerAndSubject(issuer(user), user.getSubject())
            .orElseGet(() -> signedIn(user));
    }

    public User signedIn(OidcUser user) {
        String issuer = issuer(user);
        String subject = Objects.requireNonNull(user.getSubject(), "an ID token has a subject");
        Instant issuedAt = user.getIssuedAt() != null ? user.getIssuedAt() : Instant.now();
        return signedIn(issuer, subject, user.getEmail(), user.getFullName(), groups(user), issuedAt);
    }

    User signedIn(String issuer, String subject, String email, String name) {
        return signedIn(issuer, subject, email, name, null, null);
    }

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
            return userRepository.findByIssuerAndSubject(issuer, subject).orElseThrow(() -> raced);
        }
    }

    private static String issuer(OidcUser user) {
        return Objects.requireNonNull(user.getIssuer(), "an ID token names its issuer").toExternalForm();
    }
}
