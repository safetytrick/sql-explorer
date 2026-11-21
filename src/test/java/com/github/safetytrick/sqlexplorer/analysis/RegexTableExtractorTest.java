package com.github.safetytrick.sqlexplorer.analysis;

import java.util.List;

import com.github.safetytrick.sqlexplorer.avro.TableReference;
import com.github.safetytrick.sqlexplorer.schema.LoadedSchema;
import net.sf.jsqlparser.JSQLParserException;
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

    @Test
    void forUpdateLockSuffixIsNotATableReference() {
        assertThat(extract(" SELECT `id` FROM `branch` FOR UPDATE SKIP LOCKED "))
            .extracting(TableReference::getNormalizedTableName)
            .containsExactly("branch");
    }

    @Test
    void findsATableThatEndsTheQuery() {
        assertThat(extract("\n    SELECT\n        `time_updated` \n    FROM\n        `trigger_stat_get_user_list`"))
            .extracting(TableReference::getNormalizedSchemaName, TableReference::getNormalizedTableName)
            .containsExactly(tuple("tdb", "trigger_stat_get_user_list"));

        assertThat(extract(" SELECT * FROM `other` . `branch`"))
            .extracting(TableReference::getNormalizedSchemaName, TableReference::getNormalizedTableName)
            .containsExactly(tuple("other", "branch"));
    }

    @Test
    void onUpdateReferentialActionsAreNotTableReferences() {
        assertThat(extract(" CREATE TABLE `link_cta` ( `id` INT8 (?) NOT NULL AUTO_INCREMENT ,"
                               + " `time_created` DATETIME NOT NULL DEFAULT NOW ,"
                               + " `time_updated` DATETIME NOT NULL DEFAULT NOW ON UPDATE NOW ) "))
            .extracting(TableReference::getNormalizedTableName)
            .containsExactly("link_cta");

        assertThat(extract(" CREATE TABLE `t` ( `id` INT8 (?) , FOREIGN KEY ( `id` )"
                               + " REFERENCES `other` ( `id` ) ON DELETE CASCADE ON UPDATE RESTRICT ) "))
            .extracting(TableReference::getNormalizedTableName)
            .doesNotContain("restrict");

        assertThat(extract(" CREATE TABLE `t` ( `id` INT8 (?) , FOREIGN KEY ( `id` )"
                               + " REFERENCES `other` ( `id` ) ON UPDATE NO ACTION ) "))
            .extracting(TableReference::getNormalizedTableName)
            .doesNotContain("no");

        assertThat(extract(" CREATE TABLE `t` ( `id` INT8 (?) , FOREIGN KEY ( `id` )"
                               + " REFERENCES `other` ( `id` ) ON UPDATE CASCADE ) "))
            .extracting(TableReference::getNormalizedTableName)
            .doesNotContain("cascade");

        assertThat(extract(" CREATE TABLE `t` ( `a` INT , `b` DATETIME ON UPDATE CURRENT_TIMESTAMP ,"
                               + " FOREIGN KEY ( `a` ) REFERENCES `o` ( `a` ) ON UPDATE SET NULL ) "))
            .extracting(TableReference::getNormalizedTableName)
            .containsExactlyInAnyOrder("t", "o")
            .doesNotContain("current_timestamp", "set", "null");
    }

    @Test
    void updateAsAPrivilegeOrTriggerEventDoesNotCaptureOn() {
        assertThat(extract(" GRANT CREATE , SELECT , INSERT, UPDATE ON `search` . `entity_favorite` TO ? @? "))
            .extracting(TableReference::getNormalizedTableName)
            .doesNotContain("on");

        assertThat(extract(" CREATE DEFINER = `rdsadmin` @`localhost` TRIGGER `block_user_u`"
                               + " BEFORE UPDATE ON `user` FOR EACH ROW BEGIN IF ? THEN END IF ; END "))
            .extracting(TableReference::getNormalizedTableName)
            .doesNotContain("on");
    }

    @Test
    void aQuotedOnIsStillATable() {
        assertThat(extract(" SELECT * FROM `on` "))
            .extracting(TableReference::getNormalizedTableName)
            .containsExactly("on");
    }

    @Test
    void aTableNameEndingInOnDoesNotSuppressARealUpdate() {
        assertThat(extract(" SELECT * FROM `session` ; UPDATE `branch` SET `a` = ? "))
            .extracting(TableReference::getNormalizedTableName)
            .containsExactly("session", "branch");
    }

    @Test
    void findsEveryTableWhenNoAliasSeparatesThem() {
        assertThat(extract(" SELECT * FROM `a` JOIN `b` ON `a` . `id` = `b` . `a_id` "))
            .extracting(TableReference::getNormalizedTableName)
            .containsExactly("a", "b");

        assertThat(extract(" SELECT * FROM a JOIN b JOIN c ON x "))
            .extracting(TableReference::getNormalizedTableName)
            .containsExactly("a", "b", "c");
    }

    @Test
    void extractsTheTableFromDdlPositions() {
        assertThat(extract(" SHOW CREATE TABLE `app_main` . `account` "))
            .extracting(TableReference::getNormalizedSchemaName, TableReference::getNormalizedTableName)
            .containsExactly(tuple("app_main", "account"));

        assertThat(extract(" ALTER TABLE `accounts` ADD COLUMN `owner_email` VARCHARACTER (?) NULL "))
            .extracting(TableReference::getNormalizedTableName).containsExactly("accounts");

        assertThat(extract(" CREATE TABLE IF NOT EXISTS `signal_metadata` ( `id` BINARY (?) PRIMARY KEY ) "))
            .extracting(TableReference::getNormalizedTableName).containsExactly("signal_metadata");

        assertThat(extract(" CREATE INDEX `admin_favorite_accounts_admin_id_idx`"
                               + " ON `admin_favorite_accounts` ( `admin_id` ) "))
            .extracting(TableReference::getNormalizedTableName).containsExactly("admin_favorite_accounts");

        assertThat(extract(" CREATE DEFINER = `rdsadmin` @`localhost` TRIGGER `block_user_u`"
                               + " BEFORE UPDATE ON `user` FOR EACH ROW BEGIN END "))
            .extracting(TableReference::getNormalizedTableName).containsExactly("user");

        assertThat(extract(" TRUNCATE TABLE `sessions` "))
            .extracting(TableReference::getNormalizedTableName).containsExactly("sessions");

        assertThat(extract(" CREATE TABLE `child` ( `p` INT , FOREIGN KEY ( `p` ) REFERENCES `parent`(`id`) ) "))
            .extracting(TableReference::getNormalizedTableName)
            .containsExactlyInAnyOrder("child", "parent");
    }

    @Test
    void extractsTheGrantedTableButNotTheSchemaWildcard() {
        assertThat(extract(" GRANT ALL PRIVILEGES ON `app_core` . `report_totals` TO ? @? WITH GRANT OPTION "))
            .extracting(TableReference::getNormalizedSchemaName, TableReference::getNormalizedTableName)
            .containsExactly(tuple("app_core", "report_totals"));

        assertThat(extract(" GRANT ALL PRIVILEGES ON `app_admin` . * TO ? @? "))
            .extracting(TableReference::getNormalizedTableName).isEmpty();

        assertThat(extract(" REVOKE SELECT ON `app_main` . `account` FROM ? @? "))
            .extracting(TableReference::getNormalizedTableName).containsExactly("account");
    }

    @Test
    void doesNotTreatNonTableDdlObjectsAsTables() {
        assertThat(extract(" DROP FUNCTION IF EXISTS `rds_version` ")).isEmpty();
        assertThat(extract(" DROP PROCEDURE IF EXISTS `mysql` . `rds_start_upgrade_prechecks` ")).isEmpty();
        assertThat(extract(" CREATE SCHEMA `ab_testing` ")).isEmpty();
        assertThat(extract(" ALTER SYSTEM_USER ? @? IDENTIFIED BY ? ")).isEmpty();
        assertThat(extract(" SHOW CREATE FUNCTION `mysql` . `rds_is_semi_sync` ")).isEmpty();
        assertThat(extract(" SHOW BINARY LOGS ")).isEmpty();
        assertThat(extract(" CREATE DEFINER = ? @? FUNCTION `rds_version` ( ) RETURNS VARCHARACTER (?) ")).isEmpty();
    }

    @Test
    void doesNotExtractNamesThatAreGoneOrTransient() {
        assertThat(extract(" DROP TABLE IF EXISTS `mysql` . `rds_upgrade_prechecks` ")).isEmpty();

        assertThat(extract(" CREATE TEMPORARY TABLE IF NOT EXISTS `HT_file_processing` ( `id` BINARY (?) ) "))
            .isEmpty();
    }

    private static LoadedSchema schema() throws JSQLParserException {
        return SchemaFixture.fromSql("""
            create table tdb.category (id bigint not null, name varchar(255), primary key (id));
            """).schema();
    }

    @Test
    void reportsExtractedNamesTheSchemaDoesNotKnow() throws JSQLParserException {
        assertThat(extractor.unknownTables("tdb", " SELECT * FROM `category` JOIN `not_a_table` ON ? ", schema()))
            .extracting(TableReference::getNormalizedTableName)
            .containsExactly("not_a_table");
    }

    @Test
    void aFullyKnownStatementReportsNothing() throws JSQLParserException {
        assertThat(extractor.unknownTables("tdb", " SELECT * FROM `category` ", schema())).isEmpty();
    }

    @Test
    void caseAndBackticksDoNotMakeATableUnknown() throws JSQLParserException {
        assertThat(extractor.unknownTables("tdb", " SELECT * FROM CATEGORY ", schema())).isEmpty();
        assertThat(extractor.unknownTables("tdb", " SELECT * FROM `TDB` . `Category` ", schema())).isEmpty();
    }

    @Test
    void withoutASchemaNothingIsUnknown() {
        assertThat(extractor.unknownTables("tdb", " SELECT * FROM `not_a_table` ", null)).isEmpty();
    }

    @Test
    void anUnqualifiedNameWithNoDefaultDatabaseIsNotReported() throws JSQLParserException {
        assertThat(extractor.unknownTables(null, " SELECT a FROM orders ", schema())).isEmpty();
        assertThat(extractor.unknownTables(null, " SHOW FULL TABLES FROM `app_production` ", schema())).isEmpty();
    }

    @Test
    void theCaptureQueryWithNoDefaultDatabaseIsAnalysedNotThrown() throws JSQLParserException {
        assertThat(extractor.unknownTables(null,
            " SELECT QUERY , `db` , `digest` FROM `sys` . `x$statement_analysis` ", schema())).isEmpty();
    }

    @Test
    void aQualifiedNameIsStillCheckedWithNoDefaultDatabase() throws JSQLParserException {
        assertThat(extractor.unknownTables(null, " SELECT * FROM `tdb` . `not_a_table` ", schema()))
            .extracting(TableReference::getNormalizedTableName)
            .containsExactly("not_a_table");
    }

    @Test
    void repeatedUnknownNamesAreReportedOnce() throws JSQLParserException {
        assertThat(extractor.unknownTables("tdb",
            " SELECT * FROM `ghost` JOIN `ghost` ON ? JOIN `category` ON ? JOIN `spectre` ON ? ", schema()))
            .extracting(TableReference::getNormalizedTableName)
            .containsExactly("ghost", "spectre");
    }

    @Test
    void aReferenceWithoutASchemaNormalizes() {
        TableReference unqualified = TableReference.newTableReference(null, "Orders");

        assertThat(unqualified.normalized().getNormalizedTableName()).isEqualTo("orders");
        assertThat(unqualified.normalized().getSchemaName()).isNull();
        assertThat(TableReference.newTableReference(null, "orders").isNormalized()).isTrue();
    }
}
