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
     * `ON UPDATE <action>` is a DDL clause, not an UPDATE statement, and its
     * action word was being reported as a table. These are the exact
     * statements from the regression corpus, which is where `app_del.now`,
     * `app_del.no`, `app_del.restrict`, `app_del.cascade`, `app_main.cascade`,
     * `app_admin.cascade` and `app_signals.now` came from — schema-tree nodes for
     * tables that do not exist. CREATE is in `ignoredFirstTokens`, so these
     * never reach jsqlparser and the regex is the only thing that reads them.
     */
    @Test
    void onUpdateReferentialActionsAreNotTableReferences() {
        // the table being created is now extracted (the DDL positions); what
        // must never come back is the referential action beside it
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

        // the whole family, including actions this corpus has not produced yet
        assertThat(extract(" CREATE TABLE `t` ( `a` INT , `b` DATETIME ON UPDATE CURRENT_TIMESTAMP ,"
                               + " FOREIGN KEY ( `a` ) REFERENCES `o` ( `a` ) ON UPDATE SET NULL ) "))
            .extracting(TableReference::getNormalizedTableName)
            .containsExactlyInAnyOrder("t", "o")
            .doesNotContain("current_timestamp", "set", "null");
    }

    /**
     * The mirror case: in a privilege list and a trigger event, UPDATE is
     * followed by ON, and the optional schema group cannot absorb it because
     * no dot follows — so the keyword itself was captured. Live: `mysql.on`.
     */
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

    /** A table genuinely named `on` has to be quoted, and still counts. */
    @Test
    void aQuotedOnIsStillATable() {
        assertThat(extract(" SELECT * FROM `on` "))
            .extracting(TableReference::getNormalizedTableName)
            .containsExactly("on");
    }

    /**
     * The new lookbehind is word-boundary anchored, so a real UPDATE statement
     * on a table whose name merely ends in "on" is unaffected. Without the
     * `\\b` the preceding "…on " would suppress it.
     */
    @Test
    void aTableNameEndingInOnDoesNotSuppressARealUpdate() {
        assertThat(extract(" SELECT * FROM `session` ; UPDATE `branch` SET `a` = ? "))
            .extracting(TableReference::getNormalizedTableName)
            .containsExactly("session", "branch");
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

    /**
     * DDL names its table in a position the DML pattern cannot see, and
     * everything in `ignoredFirstTokens` reaches only this extractor — so
     * before these positions existed, 1,059 skipped profiles carried no table
     * at all and a table that is only ever created, altered or granted on was
     * invisible to the schema tree. Statements are the shapes the regression
     * corpus actually contains.
     */
    @Test
    void extractsTheTableFromDdlPositions() {
        // 301 profiles — much the most common shape, and an ORM/migration probe
        assertThat(extract(" SHOW CREATE TABLE `app_main` . `demos` "))
            .extracting(TableReference::getNormalizedSchemaName, TableReference::getNormalizedTableName)
            .containsExactly(tuple("app_main", "demos"));

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

        // the foreign key's parent, named inside a CREATE body with no space
        assertThat(extract(" CREATE TABLE `child` ( `p` INT , FOREIGN KEY ( `p` ) REFERENCES `parent`(`id`) ) "))
            .extracting(TableReference::getNormalizedTableName)
            .containsExactlyInAnyOrder("child", "parent");
    }

    /**
     * A privilege object is `ON s.t TO|FROM`, and the trailing TO/FROM is what
     * separates it from a JOIN condition — the only thing in these statements
     * that is reliably a table.
     */
    @Test
    void extractsTheGrantedTableButNotTheSchemaWildcard() {
        assertThat(extract(" GRANT ALL PRIVILEGES ON `cl` . `stat_get_doc_names` TO ? @? WITH GRANT OPTION "))
            .extracting(TableReference::getNormalizedSchemaName, TableReference::getNormalizedTableName)
            .containsExactly(tuple("cl", "stat_get_doc_names"));

        // `s`.* is a whole schema, not a table, and cannot match: * is not an identifier
        assertThat(extract(" GRANT ALL PRIVILEGES ON `app_admin` . * TO ? @? "))
            .extracting(TableReference::getNormalizedTableName).isEmpty();

        assertThat(extract(" REVOKE SELECT ON `app_main` . `demos` FROM ? @? "))
            .extracting(TableReference::getNormalizedTableName).containsExactly("demos");
    }

    /**
     * These sit in the same statements and name things that are not tables.
     * Matching an identifier after a bare verb is exactly how `ON UPDATE NOW`
     * became a table called `now`, so every DDL position requires its
     * object-type keyword.
     */
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

    /**
     * Two positions left out deliberately, both because the name they yield is
     * absent from the schema by construction — which the ingest-time check
     * would report as a failure on every such profile.
     */
    @Test
    void doesNotExtractNamesThatAreGoneOrTransient() {
        // a dropped table is not in the schema any more; 1 profile in the corpus
        assertThat(extract(" DROP TABLE IF EXISTS `mysql` . `rds_upgrade_prechecks` ")).isEmpty();

        // Hibernate's bulk-operation temp table: never in the schema, transient
        // by design. Excluded for free — TEMPORARY sits between CREATE and TABLE.
        assertThat(extract(" CREATE TEMPORARY TABLE IF NOT EXISTS `HT_file_processing` ( `id` BINARY (?) ) "))
            .isEmpty();
    }

    private static LoadedSchema schema() throws JSQLParserException {
        return SchemaFixture.fromSql("""
            create table tdb.folders (id bigint not null, name varchar(255), primary key (id));
            """).schema();
    }

    /**
     * The regex reads statements the parser never sees, so nothing else
     * corroborates its output — which is how `ON UPDATE NOW` became a table
     * called `now` and sat in the schema tree unnoticed. Where the environment
     * schema is loaded it can corroborate: a name that is not a table there
     * means the pattern matched something that is not a table reference.
     */
    @Test
    void reportsExtractedNamesTheSchemaDoesNotKnow() throws JSQLParserException {
        assertThat(extractor.unknownTables("tdb", " SELECT * FROM `folders` JOIN `not_a_table` ON ? ", schema()))
            .extracting(TableReference::getNormalizedTableName)
            .containsExactly("not_a_table");
    }

    @Test
    void aFullyKnownStatementReportsNothing() throws JSQLParserException {
        assertThat(extractor.unknownTables("tdb", " SELECT * FROM `folders` ", schema())).isEmpty();
    }

    /** Backticks and casing are not a difference; the lookup normalizes both sides. */
    @Test
    void caseAndBackticksDoNotMakeATableUnknown() throws JSQLParserException {
        assertThat(extractor.unknownTables("tdb", " SELECT * FROM FOLDERS ", schema())).isEmpty();
        assertThat(extractor.unknownTables("tdb", " SELECT * FROM `TDB` . `Folders` ", schema())).isEmpty();
    }

    /**
     * With no schema there is nothing to check against, and "no schema" must
     * not read as "nothing exists" — analysis runs without one in tests and on
     * the no-schema convenience path, and flagging every table there would
     * bury the signal this exists to raise.
     */
    @Test
    void withoutASchemaNothingIsUnknown() {
        assertThat(extractor.unknownTables("tdb", " SELECT * FROM `not_a_table` ", null)).isEmpty();
    }

    /**
     * A statement run with no default database names its unqualified tables
     * with no schema, and a name with no schema cannot be looked up — it is
     * unverifiable, not unknown. It used to throw instead, from the lookup's
     * normalization, and the row was dropped from the corpus (sqlx-4wm).
     */
    @Test
    void anUnqualifiedNameWithNoDefaultDatabaseIsNotReported() throws JSQLParserException {
        assertThat(extractor.unknownTables(null, " SELECT a FROM orders ", schema())).isEmpty();
        assertThat(extractor.unknownTables(null, " SHOW FULL TABLES FROM `app_production` ", schema())).isEmpty();
    }

    /** The statement that showed it in production: sql-explorer's own capture query, run with no database. */
    @Test
    void theCaptureQueryWithNoDefaultDatabaseIsAnalysedNotThrown() throws JSQLParserException {
        assertThat(extractor.unknownTables(null,
            " SELECT QUERY , `db` , `digest` FROM `sys` . `x$statement_analysis` ", schema())).isEmpty();
    }

    /** Qualified names are still checked when there is no default database. */
    @Test
    void aQualifiedNameIsStillCheckedWithNoDefaultDatabase() throws JSQLParserException {
        assertThat(extractor.unknownTables(null, " SELECT * FROM `tdb` . `not_a_table` ", schema()))
            .extracting(TableReference::getNormalizedTableName)
            .containsExactly("not_a_table");
    }

    /** One entry per name, in encounter order, however often it is referenced. */
    @Test
    void repeatedUnknownNamesAreReportedOnce() throws JSQLParserException {
        assertThat(extractor.unknownTables("tdb",
            " SELECT * FROM `ghost` JOIN `ghost` ON ? JOIN `folders` ON ? JOIN `spectre` ON ? ", schema()))
            .extracting(TableReference::getNormalizedTableName)
            .containsExactly("ghost", "spectre");
    }

    /** schemaName is nullable in the Avro schema, so normalizing a reference without one must not throw (sqlx-4wm). */
    @Test
    void aReferenceWithoutASchemaNormalizes() {
        TableReference unqualified = TableReference.newTableReference(null, "Orders");

        assertThat(unqualified.normalized().getNormalizedTableName()).isEqualTo("orders");
        assertThat(unqualified.normalized().getSchemaName()).isNull();
        assertThat(TableReference.newTableReference(null, "orders").isNormalized()).isTrue();
    }
}
