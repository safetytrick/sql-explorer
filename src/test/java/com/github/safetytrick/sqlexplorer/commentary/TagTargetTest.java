package com.github.safetytrick.sqlexplorer.commentary;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TagTargetTest {

    @Test
    void aTargetKnowsItsPathFormWhenItHasOne() {
        assertThat(TagTarget.ofSchema("app_main").path()).isEqualTo("/schema/app_main");
        assertThat(TagTarget.ofTable("app_main", "orders").path()).isEqualTo("/schema/app_main/table/orders");
        assertThat(TagTarget.ofQuery("v3:abc").path()).isEqualTo("/query/v3:abc");
    }

    @Test
    void aNameContainingASeparatorHasNoPathForm() {
        assertThat(TagTarget.ofTable("probe", "a/b").path()).isNull();
        assertThat(TagTarget.ofSchema("a/b").path()).isNull();
        assertThat(TagTarget.ofTable("a/b", "orders").path()).isNull();
    }

    @Test
    void aNameContainingASeparatorIsStillAddressable() {
        TagTarget odd = TagTarget.ofTable("probe", "a/b").validated();

        assertThat(odd.type()).isEqualTo("table");
        assertThat(odd.schema()).isEqualTo("probe");
        assertThat(odd.table()).isEqualTo("a/b");
    }

    @Test
    void theUnusedPartsAreEmptyNotNull() {
        TagTarget schema = TagTarget.ofSchema("app_main");

        assertThat(schema.table()).isEmpty();
        assertThat(schema.digest()).isEmpty();
    }

    @Test
    void aShapeThatAddressesNothingIsRefused() {
        assertThatThrownBy(() -> new TagTarget("index", "app_main", "orders", "").validate())
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("type must be");
        assertThatThrownBy(() -> new TagTarget("table", "app_main", "", "").validate())
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("needs a table");
        assertThatThrownBy(() -> new TagTarget("schema", "", "", "").validate())
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("needs a schema");
        assertThatThrownBy(() -> new TagTarget("query", "", "", "").validate())
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("needs a digest");
    }

    @Test
    void partsThatDoNotBelongToTheKindAreRefused() {
        assertThatThrownBy(() -> new TagTarget("schema", "app_main", "orders", "").validate())
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no table");
        assertThatThrownBy(() -> new TagTarget("query", "app_main", "", "v3:abc").validate())
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no schema");
    }

    @Test
    void nullAndEmptyPartsAreTheSameTarget() {
        assertThat(new TagTarget("schema", "app_main", null, null))
            .isEqualTo(new TagTarget("schema", "app_main", "", ""));
        assertThat(new TagTarget("schema", "app_main", null, null).hashCode())
            .isEqualTo(new TagTarget("schema", "app_main", "", "").hashCode());
    }

    @Test
    void aLegacyPathBecomesATarget() {
        assertThat(TagPath.require("/schema/app_main/table/orders").toTarget())
            .isEqualTo(TagTarget.ofTable("app_main", "orders"));
    }

    @Test
    void aColumnTargetNeedsItsTableAndColumn() {
        assertThat(TagTarget.ofColumn("app", "account", "status").validatedForTags().type()).isEqualTo("column");
        assertThatThrownBy(() -> new TagTarget("column", "app", "account", "", "").validate())
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("needs a column");
        assertThatThrownBy(() -> new TagTarget("column", "app", "", "", "status").validate())
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("needs a table");
        assertThatThrownBy(() -> new TagTarget("table", "app", "account", "", "status").validate())
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no column");
    }

    @Test
    void theColumnIsPartOfTheIdentity() {
        assertThat(TagTarget.ofColumn("app", "account", "status"))
            .isNotEqualTo(TagTarget.ofColumn("app", "account", "name"))
            .isNotEqualTo(TagTarget.ofTable("app", "account"));
    }

    @Test
    void theEnvironmentIsATargetButNotATagTarget() {
        assertThat(TagTarget.ofEnvironment().validated().type()).isEqualTo("environment");
        assertThat(TagTarget.ofEnvironment().path()).isNull();
        assertThatThrownBy(() -> TagTarget.ofEnvironment().validatedForTags().validate())
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not the environment");
        assertThatThrownBy(() -> new TagTarget("environment", "app", "", "").validate())
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no schema");
    }
}
