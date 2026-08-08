package com.github.safetytrick.sqlexplorer.analysis;

import java.util.List;

import com.github.safetytrick.sqlexplorer.avro.TableReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class RegexTableExtractorTest {

    private final RegexTableExtractor extractor = new RegexTableExtractor();

    private List<TableReference> extract(String sql) {
        return extractor.extractTables("tdb", sql);
    }

    @Test
    void extractsFromInsertUpdateFromAndJoin() {
        assertThat(extract(" SELECT a FROM `member_session` ms JOIN `other` . `branch` b ON ms.branch_id = b.id "))
            .extracting(TableReference::getNormalizedSchemaName, TableReference::getNormalizedTableName)
            .containsExactly(tuple("tdb", "member_session"), tuple("other", "branch"));

        assertThat(extract(" UPDATE `member_session` SET `checkout_date` = ? WHERE `id` = ? "))
            .extracting(TableReference::getNormalizedTableName)
            .containsExactly("member_session");

        assertThat(extract(" INSERT INTO `video_metadata` ( `external_id` ) VALUES (?) "))
            .extracting(TableReference::getNormalizedTableName)
            .containsExactly("video_metadata");
    }

    /**
     * Regression: the UPDATE branch fired on the keyword inside
     * {@code ON DUPLICATE KEY UPDATE}, reporting the first assigned column
     * ("size") as a table.
     */
    @Test
    void onDuplicateKeyUpdateIsNotATableReference() {
        String sql = """

                INSERT
                INTO
                    `video_metadata`
                    (
                        `external_id` , `size` , `duration` , `width` , `height` , `input_file_type`
                    )
                VALUES
                    (...) AS `new_row`
                        ON DUPLICATE KEY UPDATE `size` = `new_row` . `size` , `duration` = `new_row` . `duration` , `width` = `new_row` . `width` , `height` = `new_row` . `height` , `input_file_type` = `new_row` . `input_file_type`
            """;

        assertThat(extract(sql))
            .extracting(TableReference::getNormalizedTableName)
            .containsExactly("video_metadata");
    }

    @Test
    void onDuplicateKeyUpdateIsBlockedAcrossCaseAndLineBreaks() {
        assertThat(extract(" insert into t1 (a) values (?) as nr on duplicate key update a = nr.a , b = nr.b "))
            .extracting(TableReference::getNormalizedTableName)
            .containsExactly("t1");

        assertThat(extract(" INSERT INTO t2 (a) VALUES (?) ON DUPLICATE KEY\n        UPDATE a = ? , b = ? "))
            .extracting(TableReference::getNormalizedTableName)
            .containsExactly("t2");
    }

    /** The FOR UPDATE lock suffix must not capture its modifiers either. */
    @Test
    void forUpdateLockSuffixIsNotATableReference() {
        assertThat(extract(" SELECT `id` FROM `branch` FOR UPDATE SKIP LOCKED "))
            .extracting(TableReference::getNormalizedTableName)
            .containsExactly("branch");
    }

    /**
     * The performance schema hands over queries with no trailing whitespace,
     * so the last table in the statement sat right against the end of the
     * string. The pattern used to close the table name by consuming the
     * whitespace after it, so this found nothing at all — and an empty result
     * made {@link IgnoredSchemas#ignoreAll} vacuously true, which skipped
     * analysis of the query entirely.
     */
    @Test
    void findsATableThatEndsTheQuery() {
        assertThat(extract("\n    SELECT\n        `time_updated` \n    FROM\n        `trigger_stat_get_user_list`"))
            .extracting(TableReference::getNormalizedSchemaName, TableReference::getNormalizedTableName)
            .containsExactly(tuple("tdb", "trigger_stat_get_user_list"));

        assertThat(extract(" SELECT * FROM `other` . `branch`"))
            .extracting(TableReference::getNormalizedSchemaName, TableReference::getNormalizedTableName)
            .containsExactly(tuple("other", "branch"));
    }

    /**
     * Without an alias between them, the whitespace consumed after one table
     * was the same whitespace the next keyword needed to match on — so only
     * the first table was ever reported. The existing multi-table case passes
     * only because its tables happen to carry aliases.
     */
    @Test
    void findsEveryTableWhenNoAliasSeparatesThem() {
        assertThat(extract(" SELECT * FROM `a` JOIN `b` ON `a` . `id` = `b` . `a_id` "))
            .extracting(TableReference::getNormalizedTableName)
            .containsExactly("a", "b");

        assertThat(extract(" SELECT * FROM a JOIN b JOIN c ON x "))
            .extracting(TableReference::getNormalizedTableName)
            .containsExactly("a", "b", "c");
    }
}
