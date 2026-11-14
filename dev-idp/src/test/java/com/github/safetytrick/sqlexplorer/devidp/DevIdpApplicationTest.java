package com.github.safetytrick.sqlexplorer.devidp;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Boots the IdP and signs in the way a browser and a relying party would:
 * authorize, the login form, the code redirect, the token exchange with the
 * PKCE verifier, then userinfo. The issuer is pinned to a host and port the
 * test never connects to, which is the browser/in-network split in miniature:
 * every endpoint is reached on the random port, yet {@code iss} stays fixed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"dev-idp.issuer=http://idp.example.test:4200/idp",
                "dev-idp.key-file=build/test-idp/signing-key.json",
                "dev-idp.extra-origins=https://tunnel.example.test/"})
class DevIdpApplicationTest {

    private static final String ISSUER = "http://idp.example.test:4200/idp";
    private static final String REDIRECT_URI = "http://localhost:4200/api/sql-explorer/login/oauth2/code/sql-explorer";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Served under a path so the UI's nginx can proxy it on the same origin as the app. */
    private static final String CONTEXT_PATH = "/idp";

    @LocalServerPort
    int port;

    @Test
    void discoveryAdvertisesTheFixedIssuerAndTheOidcEndpoints() throws Exception {
        HttpResponse<String> response = newClient().send(get("/.well-known/openid-configuration"), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode discovery = JSON.readTree(response.body());

        assertThat(discovery.get("issuer").asString()).isEqualTo(ISSUER);
        assertThat(discovery.get("authorization_endpoint").asString()).isEqualTo(ISSUER + "/oauth2/authorize");
        assertThat(discovery.get("token_endpoint").asString()).isEqualTo(ISSUER + "/oauth2/token");
        assertThat(discovery.get("jwks_uri").asString()).isEqualTo(ISSUER + "/oauth2/jwks");
        assertThat(discovery.get("userinfo_endpoint").asString()).isEqualTo(ISSUER + "/userinfo");
        assertThat(strings(discovery.get("code_challenge_methods_supported"))).contains("S256");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "viewer   | sqlx:viewer:local",
            "runner   | sqlx:viewer:local,sqlx:runner:local/app",
            "author   | sqlx:viewer:local,sqlx:author:local/app",
            "mixed    | sqlx:viewer:local,sqlx:viewer:production,sqlx:runner:local,sqlx:author:production/app",
            "admin    | sqlx:admin",
            "nogroups | ''",
    })
    void eachCannedUserSignsInAndCarriesTheirGroups(String username, String expectedGroups) throws Exception {
        List<String> expected = expectedGroups.isEmpty() ? List.of() : List.of(expectedGroups.split(","));

        Tokens tokens = signIn(username, username);

        Jwt idToken = NimbusJwtDecoder.withJwkSetUri(url("/oauth2/jwks")).build().decode(tokens.idToken());
        assertThat(idToken.getIssuer().toString()).isEqualTo(ISSUER);
        assertThat(idToken.getSubject()).isEqualTo(username);
        assertThat(idToken.getAudience()).containsExactly("sql-explorer");
        assertThat(idToken.getClaimAsStringList("groups")).containsExactlyElementsOf(expected);

        HttpResponse<String> userInfo = newClient().send(HttpRequest.newBuilder(URI.create(url("/userinfo")))
                .header("Authorization", "Bearer " + tokens.accessToken()).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(userInfo.statusCode()).isEqualTo(200);
        JsonNode claims = JSON.readTree(userInfo.body());
        assertThat(claims.get("sub").asString()).isEqualTo(username);
        assertThat(claims.get("preferred_username").asString()).isEqualTo(username);
        assertThat(strings(claims.get("groups"))).containsExactlyElementsOf(expected);
    }

    @Test
    void aWrongPasswordDoesNotSignIn() throws Exception {
        HttpClient client = newClient();
        String csrf = csrfToken(client);
        HttpResponse<String> login = client.send(post("/login", Map.of("username", "admin", "password", "wrong", "_csrf", csrf)),
                HttpResponse.BodyHandlers.ofString());
        assertThat(login.headers().firstValue("Location")).hasValueSatisfying(location -> assertThat(location).contains("/login?error"));
    }

    @Test
    void anUnregisteredRedirectUriIsRefused() throws Exception {
        // signed in first: an anonymous request is sent to the login form before the redirect is checked
        HttpClient client = newClient();
        HttpResponse<String> login = client.send(post("/login", Map.of("username", "admin", "password", "admin", "_csrf", csrfToken(client))),
                HttpResponse.BodyHandlers.ofString());
        assertThat(login.headers().firstValue("Location")).hasValueSatisfying(location -> assertThat(location).doesNotContain("error"));

        HttpResponse<String> response = client.send(get("/oauth2/authorize?" + form(Map.of(
                "response_type", "code",
                "client_id", "sql-explorer",
                "scope", "openid",
                "redirect_uri", "http://elsewhere.example.test/callback",
                "code_challenge", "x".repeat(43),
                "code_challenge_method", "S256"))), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(400);
    }

    /**
     * Behind the UI's nginx the browser is on localhost:4200, and the IdP sees
     * the proxy's request. The login redirect has to be built from the
     * forwarded headers, or the browser is sent to a port it cannot reach.
     */
    @Test
    void theLoginRedirectFollowsTheProxysForwardedHeaders() throws Exception {
        HttpResponse<String> authorize = newClient().send(HttpRequest.newBuilder(URI.create(url("/oauth2/authorize?" + form(Map.of(
                        "response_type", "code",
                        "client_id", "sql-explorer",
                        "scope", "openid",
                        "redirect_uri", REDIRECT_URI,
                        "code_challenge", "x".repeat(43),
                        "code_challenge_method", "S256")))))
                .header("Accept", "text/html")
                .header("X-Forwarded-Proto", "http")
                .header("X-Forwarded-Host", "localhost")
                .header("X-Forwarded-Port", "4200")
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(authorize.statusCode()).isEqualTo(302);
        assertThat(authorize.headers().firstValue("Location")).hasValue("http://localhost:4200/idp/login");
    }

    /**
     * An extra origin, such as a tunnel, may be sent back to; an unregistered
     * one may not. Checked signed in: before that the IdP only sends the
     * browser to the login form.
     */
    @Test
    void anExtraOriginsCallbackIsRegisteredAndNoOtherIs() throws Exception {
        HttpClient client = newClient();
        client.send(post("/login", Map.of("username", "viewer", "password", "viewer", "_csrf", csrfToken(client))),
                HttpResponse.BodyHandlers.discarding());

        String tunnel = "https://tunnel.example.test/api/sql-explorer/login/oauth2/code/sql-explorer";
        HttpResponse<String> registered = authorizeWith(client, tunnel);
        assertThat(registered.statusCode()).isEqualTo(302);
        assertThat(registered.headers().firstValue("Location").orElseThrow()).startsWith(tunnel + "?code=");

        assertThat(authorizeWith(client, "https://elsewhere.example.test/api/sql-explorer/login/oauth2/code/sql-explorer")
                .statusCode()).isEqualTo(400);
    }

    private HttpResponse<String> authorizeWith(HttpClient client, String redirectUri) throws Exception {
        return client.send(get("/oauth2/authorize?" + form(Map.of(
                        "response_type", "code",
                        "client_id", "sql-explorer",
                        "scope", "openid",
                        "redirect_uri", redirectUri,
                        "code_challenge", "x".repeat(43),
                        "code_challenge_method", "S256"))), HttpResponse.BodyHandlers.ofString());
    }

    private record Tokens(String idToken, String accessToken) {
    }

    private Tokens signIn(String username, String password) throws Exception {
        HttpClient client = newClient();
        String verifier = randomUrlSafe(32);
        String challenge = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        String state = randomUrlSafe(16);

        HttpResponse<String> authorize = client.send(get("/oauth2/authorize?" + form(Map.of(
                "response_type", "code",
                "client_id", "sql-explorer",
                "scope", "openid profile email",
                "redirect_uri", REDIRECT_URI,
                "state", state,
                "nonce", randomUrlSafe(16),
                "code_challenge", challenge,
                "code_challenge_method", "S256"))), HttpResponse.BodyHandlers.ofString());
        assertThat(authorize.statusCode()).isEqualTo(302);
        assertThat(authorize.headers().firstValue("Location").orElseThrow()).endsWith("/login");

        String csrf = csrfToken(client);
        HttpResponse<String> login = client.send(post("/login", Map.of("username", username, "password", password, "_csrf", csrf)),
                HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).isEqualTo(302);
        String resume = login.headers().firstValue("Location").orElseThrow();
        assertThat(resume).contains("/oauth2/authorize");

        HttpResponse<String> granted = client.send(HttpRequest.newBuilder(URI.create(resume)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(granted.statusCode()).isEqualTo(302);
        URI callback = URI.create(granted.headers().firstValue("Location").orElseThrow());
        assertThat(callback.toString()).startsWith(REDIRECT_URI + "?");
        Map<String, String> query = parseQuery(callback.getRawQuery());
        assertThat(query.get("state")).isEqualTo(state);

        HttpResponse<String> token = client.send(post("/oauth2/token", Map.of(
                "grant_type", "authorization_code",
                "code", query.get("code"),
                "redirect_uri", REDIRECT_URI,
                "client_id", "sql-explorer",
                "code_verifier", verifier)), HttpResponse.BodyHandlers.ofString());
        assertThat(token.statusCode()).as(token.body()).isEqualTo(200);
        JsonNode body = JSON.readTree(token.body());
        return new Tokens(body.get("id_token").asString(), body.get("access_token").asString());
    }

    private String csrfToken(HttpClient client) throws Exception {
        String page = client.send(get("/login"), HttpResponse.BodyHandlers.ofString()).body();
        Matcher matcher = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(page);
        assertThat(matcher.find()).as("a CSRF token on the login form").isTrue();
        return matcher.group(1);
    }

    /** Keeps cookies (the IdP session) and, like a test should, does not follow redirects. */
    private static HttpClient newClient() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager()).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    private String url(String path) {
        return "http://localhost:" + port + CONTEXT_PATH + path;
    }

    private HttpRequest get(String path) {
        return HttpRequest.newBuilder(URI.create(url(path))).GET().build();
    }

    private HttpRequest post(String path, Map<String, String> fields) {
        return HttpRequest.newBuilder(URI.create(url(path)))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(fields)))
                .build();
    }

    private static String form(Map<String, String> fields) {
        return fields.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String pair : rawQuery.split("&")) {
            String[] kv = pair.split("=", 2);
            result.put(kv[0], kv.length > 1 ? java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return result;
    }

    private static List<String> strings(JsonNode array) {
        assertThat(array).as("a JSON array").isNotNull();
        assertThat(array.isArray()).isTrue();
        return array.valueStream().map(JsonNode::asString).toList();
    }

    private static String randomUrlSafe(int bytes) {
        byte[] buffer = new byte[bytes];
        new SecureRandom().nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }
}
