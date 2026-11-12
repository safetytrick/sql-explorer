package com.github.safetytrick.sqlexplorer.auth;

import java.io.IOException;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The login entry point, {@code GET /api/sql-explorer/login?return=/queries}.
 * A browser navigates here; the UI sends it here on a 401.
 *
 * <p>It remembers where to come back to, then hands over to Spring's
 * {@code /oauth2/authorization/sql-explorer}, which redirects to the provider.
 * Spring's own entry point has no "come back to" of its own, and the place the
 * user was is a page of the UI, which the backend never saw requested: the API
 * call that failed is not somewhere to return a browser to.
 *
 * <p>With sign-in off it just goes back.
 */
@Tag(name="User")
@RestController
class LoginResource {

    static final String PATH = "/login";

    /** Session attribute: where to send the browser once signed in. */
    static final String RETURN_TO = LoginResource.class.getName() + ".RETURN_TO";

    private final AuthProperties auth;

    LoginResource(AuthProperties auth) {
        this.auth = auth;
    }

    @GetMapping(PATH)
    public void login(@RequestParam(name = "return", required = false) String returnTo,
                      HttpServletRequest request, HttpServletResponse response) throws IOException {
        String target = safeReturn(returnTo);
        if (!auth.oidc()) {
            response.sendRedirect(target);
            return;
        }
        request.getSession().setAttribute(RETURN_TO, target);
        response.sendRedirect(request.getContextPath() + "/oauth2/authorization/" + SecurityConfig.REGISTRATION);
    }

    /** Where a completed sign-in goes, taken from the session once. */
    static String takeReturn(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return "/";
        }
        Object target = session.getAttribute(RETURN_TO);
        session.removeAttribute(RETURN_TO);
        return target instanceof String path ? safeReturn(path) : "/";
    }

    /**
     * A path on this site, or {@code /}. Never another host: {@code //host} and
     * {@code /\host} are both read by browsers as one, and an absolute URL is
     * refused outright, so the parameter cannot become an open redirect.
     */
    static String safeReturn(String path) {
        if (path == null || !path.startsWith("/") || path.startsWith("//") || path.startsWith("/\\")
            || path.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
            return "/";
        }
        return path;
    }
}
