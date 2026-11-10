package com.github.safetytrick.sqlexplorer.mcp;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.boot.info.BuildProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.RequestContextFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * The MCP endpoint: {@code POST /api/sql-explorer/mcp}, streamable HTTP in its
 * stateless form. Each request is one JSON-RPC message answered in its own
 * response, with no session to keep, so it sits behind nginx and ngrok like
 * any other call.
 *
 * <p>It replaces the Node sidecar that proxied the REST API over stdio, which
 * needed an install, a checkout path and environment variables in every
 * client's config. Now a client is given a URL.
 *
 * <p>Tools run on the request's own thread ({@code immediateExecution}), so
 * request-scoped beans — {@code EnvironmentContext} above all — see the MCP
 * request as they see a REST one: its {@code X-Sql-Explorer-Environment}
 * header picks the environment, and a tool's {@code environment} argument
 * overrides it.
 */
@Configuration
class McpServerConfig {

    static final String PATH = "/mcp";

    /**
     * A browser page on another site must not be able to drive the endpoint
     * through someone's localhost (DNS rebinding), so a request carrying an
     * Origin is accepted only from this machine. MCP clients send none.
     */
    private static final List<String> ALLOWED_ORIGINS = List.of(
        "http://localhost:*", "http://127.0.0.1:*", "http://[::1]:*");

    @Bean
    HttpServletStatelessServerTransport mcpTransport(JsonMapper jsonMapper) {
        return HttpServletStatelessServerTransport.builder()
            .jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
            .messageEndpoint(PATH)
            .contextExtractor(request -> McpTransportContext.create(Map.of(McpToolRunner.REQUEST, request)))
            .securityValidator(DefaultServerTransportSecurityValidator.builder().allowedOrigins(ALLOWED_ORIGINS).build())
            .build();
    }

    @Bean
    ServletRegistrationBean<HttpServletStatelessServerTransport> mcpServlet(HttpServletStatelessServerTransport transport) {
        ServletRegistrationBean<HttpServletStatelessServerTransport> registration =
            new ServletRegistrationBean<>(transport, PATH);
        registration.setName("mcp");
        registration.setLoadOnStartup(1);
        return registration;
    }

    /**
     * The servlet is not the DispatcherServlet, and with {@code @EnableWebMvc}
     * Boot registers no RequestContextFilter, so nothing would bind the request
     * that request-scoped beans resolve against.
     */
    @Bean
    FilterRegistrationBean<RequestContextFilter> mcpRequestContext() {
        FilterRegistrationBean<RequestContextFilter> registration = new FilterRegistrationBean<>(new RequestContextFilter());
        registration.addUrlPatterns(PATH);
        return registration;
    }

    @Bean(destroyMethod = "close")
    McpStatelessSyncServer mcpServer(HttpServletStatelessServerTransport transport, JsonMapper jsonMapper,
                                     List<McpToolProvider> providers, McpToolRunner runner,
                                     ObjectProvider<BuildProperties> build) {
        BuildProperties properties = build.getIfAvailable();
        List<SyncToolSpecification> tools = providers.stream()
            .flatMap(provider -> provider.tools().stream())
            .map(tool -> new SyncToolSpecification(describe(tool),
                (context, call) -> runner.run(tool, context, call)))
            .toList();
        return McpServer.sync(transport)
            .serverInfo("sql-explorer", properties != null ? properties.getVersion() : "dev")
            .instructions("""
                SQL Explorer: the queries an application runs against MySQL, read from Performance \
                Schema and analyzed — which tables and columns they touch, how they join, what they cost. \
                Start with search_queries or list_tables. Every tool but the SQL utilities reads one \
                environment: the X-Sql-Explorer-Environment header, or an `environment` argument.""")
            .jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
            .jsonSchemaValidator(new DefaultJsonSchemaValidator(jsonMapper))
            .immediateExecution(true)
            .tools(tools)
            .build();
    }

    private static McpSchema.Tool describe(McpTool tool) {
        return McpSchema.Tool.builder()
            .name(tool.name())
            .description(tool.description())
            .inputSchema(tool.inputSchema())
            .annotations(McpSchema.ToolAnnotations.builder()
                .readOnlyHint(!tool.writes())
                .destructiveHint(false)
                .build())
            .build();
    }
}
