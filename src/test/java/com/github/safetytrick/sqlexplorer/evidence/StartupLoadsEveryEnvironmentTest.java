package com.github.safetytrick.sqlexplorer.evidence;

import java.util.ArrayList;
import java.util.List;

import com.github.safetytrick.sqlexplorer.analysis.AnalysisFailureRecorder;
import com.github.safetytrick.sqlexplorer.environment.ConnectionInfo;
import com.github.safetytrick.sqlexplorer.environment.DatabaseServer;
import com.github.safetytrick.sqlexplorer.environment.Environment;
import com.github.safetytrick.sqlexplorer.environment.EnvironmentRegistry;
import com.github.safetytrick.sqlexplorer.schema.SchemaRepositoryFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Every configured environment loads at startup, not just the default.
 *
 * <p>THE BEHAVIOUR THIS REPLACED was not a decision, it fell out of which
 * environment happened to be default: a non-default one read as an EMPTY CORPUS
 * after every restart rather than as unloaded, so its pages said "0 analyzed"
 * and its unqueried report claimed every table was untouched (sqlx-v91).
 */
class StartupLoadsEveryEnvironmentTest {

    /** Records what was asked for, and fails whichever environment is named. */
    private static class Recording extends EnvironmentLoader {
        private final List<String> loaded = new ArrayList<>();
        private final String failing;

        Recording(EnvironmentRegistry registry, String failing) {
            super(List.of(), mock(SqlRepositoryManager.class), registry, mock(AnalysisFailureRecorder.class));
            this.failing = failing;
        }

        @Override
        public SqlImportStats loadConfiguredSources(String environmentName) {
            loaded.add(environmentName);
            if (environmentName.equals(failing)) {
                throw new IllegalStateException("server unreachable");
            }
            return SqlImportStats.builder().build();
        }
    }

    private static EnvironmentRegistry registryOf(String defaultName, String... names) {
        EnvironmentRegistry registry = mock(EnvironmentRegistry.class);
        List<Environment> environments = java.util.Arrays.stream(names)
            .map(name -> new Environment(name, List.of(new DatabaseServer(name,
                new ConnectionInfo("jdbc:x", "u", "p"), List.of(), mock(SchemaRepositoryFactory.class)))))
            .toList();
        when(registry.list()).thenReturn(environments);
        when(registry.getDefaultName()).thenReturn(defaultName);
        when(registry.get(anyString())).thenReturn(environments.getFirst());
        return registry;
    }

    @Test
    void everyConfiguredEnvironmentIsLoaded() {
        Recording loader = new Recording(registryOf("regression", "local", "regression", "staging"), null);

        loader.loadConfiguredSourcesAtStartup();

        assertThat(loader.loaded).containsExactlyInAnyOrder("regression", "local", "staging");
    }

    /**
     * The default goes first: it is the one most likely to be opened, and the
     * resident cap evicts least-recently-used, so load order decides what is
     * still in heap when startup finishes.
     */
    @Test
    void theDefaultLoadsFirst() {
        Recording loader = new Recording(registryOf("regression", "local", "regression", "staging"), null);

        loader.loadConfiguredSourcesAtStartup();

        assertThat(loader.loaded.getFirst()).isEqualTo("regression");
    }

    @Test
    void theDefaultIsNotLoadedTwice() {
        Recording loader = new Recording(registryOf("regression", "regression"), null);

        loader.loadConfiguredSourcesAtStartup();

        assertThat(loader.loaded).containsExactly("regression");
    }

    /**
     * A CHANGE, and the one worth stating: a load throws when its server is
     * unreachable, and this runs in @PostConstruct — so one bad environment
     * used to take the whole application down. Booting and reporting which
     * environment could not be read is strictly more useful.
     */
    @Test
    void oneEnvironmentThatCannotBeReadDoesNotStopTheOthers() {
        Recording loader = new Recording(registryOf("regression", "regression", "unreachable", "local"),
            "unreachable");

        loader.loadConfiguredSourcesAtStartup();

        assertThat(loader.loaded).contains("regression", "unreachable", "local");
    }

    /** Including when the failing one is the default. */
    @Test
    void evenTheDefaultFailingDoesNotStopTheOthers() {
        Recording loader = new Recording(registryOf("regression", "regression", "local"), "regression");

        loader.loadConfiguredSourcesAtStartup();

        assertThat(loader.loaded).containsExactly("regression", "local");
    }
}
