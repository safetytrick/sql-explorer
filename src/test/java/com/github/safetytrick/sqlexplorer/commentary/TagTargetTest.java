package com.github.safetytrick.sqlexplorer.commentary;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the API says a tag points at.
 *
 * <p>A target is client-supplied, so an unchecked one produces a row nothing
 * will ever look for — the defect the path format kept producing (sqlx-0lj).
 */
class TagTargetTest {

    @Test
    void aTargetKnowsItsPathFormWhenItHasOne() {
        assertThat(TagTarget.ofSchema("app_main").path()).isEqualTo("/schema/app_main");
        assertThat(TagTarget.ofTable("app_main", "orders").path()).isEqualTo("/schema/app_main/table/orders");
        assertThat(TagTarget.ofQuery("v3:abc").path()).isEqualTo("/query/v3:abc");
    }

    /**
     * THE REASON THIS TYPE EXISTS. A name containing '/' has no path form: the
     * string would not parse back and is ambiguous with a different target. Null
     * rather than a best effort, and the generated column follows the same rule
     * so the two cannot disagree (sqlx-3ai).
     */
    @Test
    void aNameContainingASeparatorHasNoPathForm() {
        assertThat(TagTarget.ofTable("probe", "a/b").path()).isNull();
        assertThat(TagTarget.ofSchema("a/b").path()).isNull();
        assertThat(TagTarget.ofTable("a/b", "orders").path()).isNull();
    }

    /** ...but it is still a perfectly good target. */
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

    /** A target carrying parts its kind does not use is a caller confusion. */
    @Test
    void partsThatDoNotBelongToTheKindAreRefused() {
        assertThatThrownBy(() -> new TagTarget("schema", "app_main", "orders", "").validate())
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no table");
        assertThatThrownBy(() -> new TagTarget("query", "app_main", "", "v3:abc").validate())
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no schema");
    }

    /** Null and empty are the same absence, so they must match as targets. */
    @Test
    void nullAndEmptyPartsAreTheSameTarget() {
        assertThat(new TagTarget("schema", "app_main", null, null))
            .isEqualTo(new TagTarget("schema", "app_main", "", ""));
        assertThat(new TagTarget("schema", "app_main", null, null).hashCode())
            .isEqualTo(new TagTarget("schema", "app_main", "", "").hashCode());
    }

    /** The legacy path form still converts, which is how old clients keep working. */
    @Test
    void aLegacyPathBecomesATarget() {
        assertThat(TagPath.require("/schema/app_main/table/orders").toTarget())
            .isEqualTo(TagTarget.ofTable("app_main", "orders"));
    }

    /** Columns are addressed like tables plus a name, and validated as strictly (sqlx-n5j.1). */
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

    /** Two columns of one table are different targets; a column is not its table. */
    @Test
    void theColumnIsPartOfTheIdentity() {
        assertThat(TagTarget.ofColumn("app", "account", "status"))
            .isNotEqualTo(TagTarget.ofColumn("app", "account", "name"))
            .isNotEqualTo(TagTarget.ofTable("app", "account"));
    }

    /** The environment is an annotation target, never a tag's (sqlx-n5j.13). */
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
