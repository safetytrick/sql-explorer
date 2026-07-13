package com.github.safetytrick.sqlexplorer.environment;

import java.util.LinkedHashMap;
import java.util.Map;

import com.github.safetytrick.sqlexplorer.schema.SchemaRepositoryFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class EnvironmentRegistryTest {

    @Test
    void environmentMarkedDefaultWinsOverDeclarationOrder() {
        assertThat(registry(definitions("regression", "local", "local")).getDefaultName())
            .isEqualTo("local");
    }

    @Test
    void multipleDefaultsFail() {
        Map<String, EnvironmentDefinition> definitions = definitions("regression", "local", "regression");
        definitions.get("local").setDefault(true);

        assertThatThrownBy(() -> registry(definitions))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("regression")
            .hasMessageContaining("local");
    }

    @Test
    void firstDeclaredIsTheDefaultWhenNoneMarked() {
        assertThat(registry(definitions("regression", "local", null)).getDefaultName())
            .isEqualTo("regression");
    }

    @Test
    void environmentNamedDefaultWinsWhenNoneMarked() {
        assertThat(registry(definitions("regression", "default", null)).getDefaultName())
            .isEqualTo("default");
    }

    private static EnvironmentRegistry registry(Map<String, EnvironmentDefinition> definitions) {
        return new EnvironmentRegistry(definitions, null, mock(SchemaRepositoryFactory.class));
    }

    private static Map<String, EnvironmentDefinition> definitions(String first, String second, String markedDefault) {
        Map<String, EnvironmentDefinition> definitions = new LinkedHashMap<>();
        for (String name : new String[] {first, second}) {
            EnvironmentDefinition definition = new EnvironmentDefinition();
            definition.setDefault(name.equals(markedDefault));
            definitions.put(name, definition);
        }
        return definitions;
    }
}
