package com.github.safetytrick.sqlexplorer.explain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class ExplainEnabledOnlyOnDisposableServersTest {

    private static final List<Path> CONFIG_DIRS = List.of(
            Path.of("src/main/resources"),
            Path.of("src/main/docker-local/config"));

    private static final Set<String> DISPOSABLE_HOSTS = Set.of("explorer-mysql", "localhost", "127.0.0.1");

    private static final Pattern JDBC_HOST = Pattern.compile("^jdbc:mysql://(\\$\\{[^}]+}|[^/:?]+)");

    private static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{[^:}]+:([^}]*)}$");

    @Test
    void live_explain_is_enabled_only_where_every_server_is_disposable() throws IOException {
        Set<String> explainEnabled = new TreeSet<>();
        Map<String, Set<String>> hostsByEnvironment = new TreeMap<>();
        for (Path file : configFiles()) {
            environments(file).forEach((name, environment) -> {
                if (Boolean.TRUE.equals(environment.get("explainEnabled"))) {
                    explainEnabled.add(name);
                }
                Set<String> hosts = hostsByEnvironment.computeIfAbsent(name, n -> new TreeSet<>());
                collectJdbcUrls(environment, url -> hosts.add(host(url)));
            });
        }
        assertThat(explainEnabled).isNotEmpty();
        for (String name : explainEnabled) {
            assertThat(hostsByEnvironment.get(name))
                    .as("environment %s enables live EXPLAIN, so every server it names must be disposable", name)
                    .isNotEmpty()
                    .allMatch(ExplainEnabledOnlyOnDisposableServersTest::isDisposable);
        }
    }

    private static boolean isDisposable(String host) {
        return DISPOSABLE_HOSTS.contains(host) || host.endsWith(".invalid");
    }

    private static String host(String jdbcUrl) {
        Matcher matcher = JDBC_HOST.matcher(jdbcUrl);
        if (!matcher.find()) {
            return jdbcUrl;
        }
        String host = matcher.group(1);
        Matcher placeholder = PLACEHOLDER.matcher(host);
        return placeholder.matches() ? placeholder.group(1) : host;
    }

    private static List<Path> configFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        for (Path dir : CONFIG_DIRS) {
            try (Stream<Path> listing = Files.list(dir)) {
                listing.filter(p -> p.toString().endsWith(".yml") || p.toString().endsWith(".yaml"))
                        .sorted()
                        .forEach(files::add);
            }
        }
        return files;
    }

    private static Map<String, Map<?, ?>> environments(Path file) throws IOException {
        Map<String, Map<?, ?>> result = new TreeMap<>();
        try (Reader reader = Files.newBufferedReader(file)) {
            for (Object document : new Yaml().loadAll(reader)) {
                if (document instanceof Map<?, ?> root && root.get("environments") instanceof Map<?, ?> environments) {
                    environments.forEach((name, value) -> {
                        if (value instanceof Map<?, ?> environment) {
                            result.put(String.valueOf(name), environment);
                        }
                    });
                }
            }
        }
        return result;
    }

    private static void collectJdbcUrls(Object node, Consumer<String> sink) {
        if (node instanceof Map<?, ?> map) {
            map.forEach((key, value) -> {
                if ("jdbcUrl".equals(key) && value instanceof String url) {
                    sink.accept(url);
                } else {
                    collectJdbcUrls(value, sink);
                }
            });
        } else if (node instanceof List<?> list) {
            list.forEach(item -> collectJdbcUrls(item, sink));
        }
    }
}
