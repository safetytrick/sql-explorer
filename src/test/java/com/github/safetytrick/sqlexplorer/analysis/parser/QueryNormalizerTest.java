package com.github.safetytrick.sqlexplorer.analysis.parser;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class QueryNormalizerTest {

    private final QueryNormalizer queryNormalizer = new QueryNormalizer();

    @Test
    public void testNormalizeEquivalentHibernateQueries() throws JSQLParserException {
        String sqlOne = """
                SELECT
                    `this_` . `id` AS `id92_0_` ,
                    `this_` . `request_time` AS `request2_92_0_` ,
                    `this_` . `event_time` AS `event3_92_0_` ,
                    `this_` . `errors` AS `errors92_0_` ,
                    `this_` . `type` AS `type92_0_` ,
                    `this_` . `recipient_id` AS `recipient6_92_0_` ,
                    `this_` . `recipient_name` AS `recipient7_92_0_` ,
                    `this_` . `begin_processing` AS `begin8_92_0_` ,
                    `this_` . `meets_criteria` AS `meets9_92_0_` ,
                    `this_` . `completed_on` AS `completed10_92_0_` ,
                    `this_` . `transaction_id` AS `transac11_92_0_` ,
                    `this_` . `pages_sent` AS `pages12_92_0_` ,
                    `this_` . `destination` AS `destina13_92_0_` ,
                    `this_` . `reason` AS `reason92_0_` ,
                    `this_` . `customer_id` AS `customer15_92_0_` ,
                    `this_` . `store_id` AS `store16_92_0_` ,
                    `this_` . `sender_id` AS `sender17_92_0_` ,
                    `this_` . `sent_to_store_id` AS `sent18_92_0_` ,
                    `this_` . `customer_visit_id` AS `customer19_92_0_`\s
                FROM
                    `client_notification` `this_`\s
                WHERE
                    `this_` . `store_id` = ?\s
                    AND `this_` . `sent_to_store_id` = ?\s
                    AND `this_` . `type` \
            """;

        String sqlTwo = """
                SELECT
                    `this_` . `id` AS `id87_0_` ,
                    `this_` . `request_time` AS `request2_87_0_` ,
                    `this_` . `event_time` AS `event3_87_0_` ,
                    `this_` . `errors` AS `errors87_0_` ,
                    `this_` . `type` AS `type87_0_` ,
                    `this_` . `recipient_id` AS `recipient6_87_0_` ,
                    `this_` . `recipient_name` AS `recipient7_87_0_` ,
                    `this_` . `begin_processing` AS `begin8_87_0_` ,
                    `this_` . `meets_criteria` AS `meets9_87_0_` ,
                    `this_` . `completed_on` AS `completed10_87_0_` ,
                    `this_` . `transaction_id` AS `transac11_87_0_` ,
                    `this_` . `pages_sent` AS `pages12_87_0_` ,
                    `this_` . `destination` AS `destina13_87_0_` ,
                    `this_` . `reason` AS `reason87_0_` ,
                    `this_` . `customer_id` AS `customer15_87_0_` ,
                    `this_` . `store_id` AS `store16_87_0_` ,
                    `this_` . `sender_id` AS `sender17_87_0_` ,
                    `this_` . `sent_to_store_id` AS `sent18_87_0_` ,
                    `this_` . `customer_visit_id` AS `customer19_87_0_`\s
                FROM
                    `client_notification` `this_`\s
                WHERE
                    `this_` . `store_id` = ?\s
                    AND `this_` . `sent_to_store_id` = ?\s
                    AND `this_` . `type` \
            """;

        String normalizedOne = queryNormalizer.normalize(CCJSqlParserUtil.parse(sqlOne));
        String normalizedTwo = queryNormalizer.normalize(CCJSqlParserUtil.parse(sqlTwo));

        assertEquals(normalizedOne, normalizedTwo);
    }
}
