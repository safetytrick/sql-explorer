package com.github.safetytrick.sqlexplorer.commentary;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a tag points at.
 *
 * <p>THE DEFECT THESE EXIST FOR. MySQL identifiers may contain {@code /} when
 * quoted, and the old tail regex {@code ([^/]+)/[^/]+$} read the type of
 * {@code /schema/probe/table/a/b} as {@code "a"}. A tag typed {@code a} is
 * invisible to {@code findAllByType("table")}, so no lens, filter or bulk
 * operation could find it, and a nonexistent type appeared in the tag manager.
 * The path is client-supplied, so any client could create that row (sqlx-0lj).
 */
class TagPathTest {

    @Test
    void aSchemaPath() {
        TagPath path = TagPath.require("/schema/app_main");

        assertThat(path.type()).isEqualTo("schema");
        assertThat(path.elements()).isEqualTo(Map.of("schema", "app_main"));
    }

    @Test
    void aTablePath() {
        TagPath path = TagPath.require("/schema/app_main/table/orders");

        assertThat(path.type()).isEqualTo("table");
        assertThat(path.elements()).isEqualTo(Map.of("schema", "app_main", "table", "orders"));
    }

    /**
     * The digest carries a colon and a version prefix, and neither is a
     * separator here — the whole remainder is the digest.
     */
    @Test
    void aQueryPath() {
        TagPath path = TagPath.require("/query/v3:7ec9bb5c128394a7");

        assertThat(path.type()).isEqualTo("query");
        assertThat(path.elements()).isEqualTo(Map.of("query", "v3:7ec9bb5c128394a7"));
    }

    /**
     * THE REGRESSION. Refused rather than read as some other table. That makes
     * such a table un-taggable, which is the honest failure — the format cannot
     * address it — and is strictly better than a row nobody can find.
     */
    @Test
    void aTableNameContainingASeparatorIsRefused() {
        assertThat(TagPath.parse("/schema/probe/table/a/b")).isNull();

        assertThatThrownBy(() -> TagPath.require("/schema/probe/table/a/b"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("/schema/{schema}/table/{table}");
    }

    /** The same for a schema, by the same rule. */
    @Test
    void aSchemaNameContainingASeparatorIsRefused() {
        assertThat(TagPath.parse("/schema/a/b")).isNull();
    }

    @Test
    void nothingElseIsAPath() {
        assertThat(TagPath.parse(null)).isNull();
        assertThat(TagPath.parse("")).isNull();
        assertThat(TagPath.parse("/")).isNull();
        assertThat(TagPath.parse("app_main")).isNull();
        assertThat(TagPath.parse("/schema/")).isNull();
        assertThat(TagPath.parse("/schema")).isNull();
        assertThat(TagPath.parse("/query/")).isNull();
        assertThat(TagPath.parse("/schema/app_main/table/")).isNull();
        assertThat(TagPath.parse("/schema/app_main/column/id")).isNull();
        assertThat(TagPath.parse("/../../etc/passwd")).isNull();
    }

    /**
     * A path is bounded by MySQL's own limits, which is what lets the unique key
     * index a 191-character prefix and still be provably strict: schema and
     * table names cap at 64, so the longest path is 143.
     */
    @Test
    void theLongestPossiblePathIsWellUnderTheIndexedPrefix() {
        String name = "x".repeat(64);
        String longest = "/schema/" + name + "/table/" + name;

        assertThat(longest).hasSize(143);
        assertThat(TagPath.require(longest).type()).isEqualTo("table");
    }

    /**
     * Read back through the entity, which is how every consumer reaches this.
     * The parts come from the typed columns now, not from re-parsing a string
     * (sqlx-11v) — an untargeted tag simply has none.
     */
    @Test
    void anUntargetedTagHasNoElements() {
        assertThat(new Tag().getPathElements()).isEmpty();
    }

    @Test
    void aTargetedTagHasItsElements() {
        Tag tag = new Tag();
        tag.target(TagPath.require("/schema/app_main/table/orders").toTarget());

        assertThat(tag.getPathElements())
            .containsEntry("schema", "app_main")
            .containsEntry("table", "orders");
        assertThat(tag.getType()).isEqualTo("table");
    }

    @Test
    void aQueryTagCarriesItsDigestAndNothingElse() {
        Tag tag = new Tag();
        tag.target(TagPath.require("/query/v3:abc").toTarget());

        assertThat(tag.getPathElements()).containsExactlyEntriesOf(Map.of("query", "v3:abc"));
    }

    /** Bulk tagging derives the type the same way, so it refuses the same paths. */
    @Test
    void bulkTaggingDerivesTheSameTypes() {
        assertThat(BulkTagService.typeOf("/schema/app_main")).isEqualTo("schema");
        assertThat(BulkTagService.typeOf("/schema/app_main/table/orders")).isEqualTo("table");
        assertThat(BulkTagService.typeOf("/query/v3:abc")).isEqualTo("query");

        assertThatThrownBy(() -> BulkTagService.typeOf("/schema/probe/table/a/b"))
            .isInstanceOf(BulkTagRequestException.class);
    }

    /** A column is a table path plus one segment (sqlx-n5j.1). */
    @Test
    void aColumnPathIsAColumnTarget() {
        TagPath path = TagPath.require("/schema/app/table/account/column/status");

        assertThat(path.type()).isEqualTo("column");
        assertThat(path.toTarget()).isEqualTo(TagTarget.ofColumn("app", "account", "status"));
        assertThat(path.elements()).containsOnlyKeys("schema", "table", "column")
            .containsEntry("column", "status");
        assertThat(TagTarget.ofColumn("app", "account", "status").path())
            .isEqualTo("/schema/app/table/account/column/status");
        assertThat(BulkTagService.typeOf("/schema/app/table/account/column/status")).isEqualTo("column");
    }

    /** As strict as the table shape: an empty or separated column segment is refused. */
    @Test
    void aMalformedColumnPathIsRefused() {
        assertThat(TagPath.parse("/schema/app/table/account/column/")).isNull();
        assertThat(TagPath.parse("/schema/app/table/account/column/a/b")).isNull();
        assertThat(TagPath.parse("/schema/app/table//column/status")).isNull();
    }

    @Test
    void aColumnTagHasItsElements() {
        Tag tag = new Tag();
        tag.target(TagTarget.ofColumn("app", "account", "status"));

        assertThat(tag.getType()).isEqualTo("column");
        assertThat(tag.getPathElements()).containsEntry("column", "status").containsEntry("table", "account");
        assertThat(tag.getTarget()).isEqualTo(TagTarget.ofColumn("app", "account", "status"));
    }
}
