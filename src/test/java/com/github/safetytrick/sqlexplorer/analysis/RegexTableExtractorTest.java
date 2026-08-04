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
}
