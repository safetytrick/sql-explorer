package com.github.safetytrick.sqlexplorer.service;

import com.github.safetytrick.sqlexplorer.schema.SqlMetadataService;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import com.github.safetytrick.sqlexplorer.analysis.JoinAcrossTypesAnalyzer;
import com.github.safetytrick.sqlexplorer.analysis.NormalizedQueryAnalyzer;
import com.github.safetytrick.sqlexplorer.analysis.SqlExplorer;

@ExtendWith(SpringExtension.class)
@ComponentScan(basePackageClasses = { SqlMetadataService.class,
                                      SqlExplorer.class,
                                      JoinAcrossTypesAnalyzer.class,
                                      NormalizedQueryAnalyzer.class })
@Import({  })
public class SqlInputServiceTest {

    /*
        SELECT
        `performance_schema` . `events_waits_summary_global_by_event_name` . `EVENT_NAME` AS `events` ,
        `performance_schema` . `events_waits_summary_global_by_event_name` . `COUNT_STAR` AS `total` ,
        `performance_schema` . `events_waits_summary_global_by_event_name` . `SUM_TIMER_WAIT` AS `total_latency` ,
        `performance_schema` . `events_waits_summary_global_by_event_name` . `AVG_TIMER_WAIT` AS `avg_latency` ,
        `performance_schema` . `events_waits_summary_global_by_event_name` . `MAX_TIMER_WAIT` AS `max_latency`
    FROM
        `performance_schema` . `events_waits_summary_global_by_event_name`
    WHERE
        (
            (
                `performance_schema` . `events_waits_summary_global_by_event_name` . `EVENT_NAME` != ?
            )
            AND (
                `performance_schema` . `events_waits_summary_global_by_event_name` . `SUM_TIMER_WAIT` > ?
            )
        )
    ORDER BY
        `performance_schema` . `events_waits_summary_global_by_event_name` . `SUM_TIMER_WAIT` DESC
     */

}
