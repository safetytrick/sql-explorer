package com.github.safetytrick.sqlexplorer.profiling;

import java.util.List;

import com.github.safetytrick.sqlexplorer.profiling.ProfilePlan.Method;
import com.github.safetytrick.sqlexplorer.schema.TableStructure;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProfilePlanTest {

    private static TableStructure table(String create) throws Exception {
        return TableStructure.fromCreateTable(create, "app_main").build();
    }

    @Test
    void anIntegerKeyGetsWindowsAndOnlyCharacterColumnsAreProfiled() throws Exception {
        ProfilePlan plan = ProfilePlan.of(table("CREATE TABLE account (id BIGINT NOT NULL, "
            + "name VARCHAR(80) CHARACTER SET latin1, notes TEXT CHARACTER SET utf8mb4, "
            + "created DATETIME, hash VARBINARY(32), PRIMARY KEY (id))"), List.of(), null);

        assertThat(plan.method()).isEqualTo(Method.WINDOWS);
        assertThat(plan.key()).containsExactly("id");
        assertThat(plan.columns()).extracting(ProfilePlan.ProfiledColumn::name).containsExactly("name", "notes");
        assertThat(plan.maxRows(1_000_000L)).isEqualTo(20_000);
        // bounds, 12 windows, and a character listing per window for each of the 2 columns
        assertThat(plan.maxStatements(1_000_000L)).isEqualTo(1 + 12 * 3);
    }

    @Test
    void aCompositeKeyReadsTheEndsAndCannotBeScannedInFull() throws Exception {
        TableStructure pair = table("CREATE TABLE account_site (account_id INT NOT NULL, site VARCHAR(8) NOT NULL, "
            + "label VARCHAR(40) CHARACTER SET utf8mb4, PRIMARY KEY (account_id, site))");

        assertThat(ProfilePlan.of(pair, List.of(), null).method()).isEqualTo(Method.ENDS);
        ProfilePlan full = ProfilePlan.of(pair, List.of(), "label");
        assertThat(full.method()).isEqualTo(Method.FULL_SCAN);
        assertThat(full.runnable()).isFalse();
    }

    @Test
    void noKeyMeansNothingIsRead() throws Exception {
        ProfilePlan plan = ProfilePlan.of(table("CREATE TABLE site (name VARCHAR(40) CHARACTER SET utf8mb4, "
            + "KEY idx_name (name))"),
            List.of(), null);

        assertThat(plan.runnable()).isFalse();
        assertThat(plan.unavailable()).contains("no primary key");
    }

    @Test
    void theStatementsShownCarryTheirPatternsAndPlaceholdersForKeysNotYetRead() throws Exception {
        ProfilePlan plan = ProfilePlan.of(table("CREATE TABLE account (id BIGINT NOT NULL, "
            + "name VARCHAR(80) CHARACTER SET latin1, PRIMARY KEY (id))"), List.of(), null);

        List<String> statements = ProfileService.exampleStatements(plan);

        // every parameter is inlined: no bare placeholder is left for a person to guess at
        assertThat(statements).hasSize(5).allMatch(s -> s.startsWith("SELECT") && !s.contains(", ?,")
            && !s.contains(" ?)") && !s.contains("= ?"));
        assertThat(statements.get(0)).isEqualTo("SELECT MIN(`id`) AS min_key, MAX(`id`) AS max_key FROM "
            + "`app_main`.`account`");
        assertThat(statements.get(3)).contains("WHERE `id` >= :window_start AND `id` < :tail_first_key")
            .contains("LIMIT 1000");
        assertThat(statements.get(1)).contains("REGEXP_LIKE(w.`name`, '[^\\\\x{0000}-\\\\x{007F}]', 'c')");
    }
}
