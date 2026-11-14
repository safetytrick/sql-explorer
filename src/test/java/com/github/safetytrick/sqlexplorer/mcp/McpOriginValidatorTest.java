package com.github.safetytrick.sqlexplorer.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpOriginValidatorTest {

    private static final McpOriginValidator SIGN_IN_ON = new McpOriginValidator(true);
    private static final McpOriginValidator SIGN_IN_OFF = new McpOriginValidator(false);

    /** As the UI's nginx forwards a page opened through a tunnel. */
    private static Map<String, List<String>> throughTunnel(String origin) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("origin", List.of(origin));
        headers.put("host", List.of("tunnel.example.test"));
        headers.put("x-forwarded-proto", List.of("https"));
        headers.put("x-forwarded-host", List.of("tunnel.example.test"));
        headers.put("x-forwarded-port", List.of("443"));
        return headers;
    }

    @Test
    void withSignInOnAPageOnTheRequestsOwnAddressIsAccepted() {
        assertThatCode(() -> SIGN_IN_ON.validateHeaders(throughTunnel("https://tunnel.example.test")))
            .doesNotThrowAnyException();
    }

    @Test
    void withSignInOnAPageOnAnotherAddressIsRefused() {
        assertThatThrownBy(() -> SIGN_IN_ON.validateHeaders(throughTunnel("https://attacker.example.com")))
            .isInstanceOf(ServerTransportSecurityException.class);
        assertThatThrownBy(() -> SIGN_IN_ON.validateHeaders(throughTunnel("http://tunnel.example.test")))
            .as("the scheme is part of the origin")
            .isInstanceOf(ServerTransportSecurityException.class);
    }

    /** A rebound name is its own origin, so sign-in off must not trust it. */
    @Test
    void withSignInOffOnlyThisMachineIsAccepted() {
        assertThatThrownBy(() -> SIGN_IN_OFF.validateHeaders(throughTunnel("https://tunnel.example.test")))
            .isInstanceOf(ServerTransportSecurityException.class);
        Map<String, List<String>> local = Map.of("Origin", List.of("http://localhost:4200"), "Host", List.of("localhost:4200"));
        assertThatCode(() -> SIGN_IN_OFF.validateHeaders(local)).doesNotThrowAnyException();
        assertThatCode(() -> SIGN_IN_ON.validateHeaders(local)).doesNotThrowAnyException();
    }

    @Test
    void anMcpClientSendsNoOriginAndIsAccepted() {
        assertThatCode(() -> SIGN_IN_OFF.validateHeaders(Map.of("Host", List.of("localhost:4200"))))
            .doesNotThrowAnyException();
    }

    @Test
    void theOwnOriginLeavesOutADefaultPortAndKeepsAnyOther() {
        assertThat(McpOriginValidator.ownOrigin(throughTunnel("x"))).isEqualTo("https://tunnel.example.test");
        assertThat(McpOriginValidator.ownOrigin(Map.of(
            "Host", List.of("localhost"), "X-Forwarded-Proto", List.of("http"), "X-Forwarded-Port", List.of("4200"))))
            .isEqualTo("http://localhost:4200");
        assertThat(McpOriginValidator.ownOrigin(Map.of("Host", List.of("localhost:18180"))))
            .isEqualTo("http://localhost:18180");
        assertThat(McpOriginValidator.ownOrigin(Map.of(
            "Host", List.of("internal"), "X-Forwarded-Host", List.of("app.example.test, internal"),
            "X-Forwarded-Proto", List.of("https, http"))))
            .as("the first of a proxy chain is the browser's")
            .isEqualTo("https://app.example.test");
    }
}
