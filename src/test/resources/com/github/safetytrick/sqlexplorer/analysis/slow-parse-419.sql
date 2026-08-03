-- fixture: slow-parse-419
SELECT COUNT ( `id` ) AS `num_alerts` ,
 `session_id` ,
 `destination_master_id` ,
 `branch_id` ,
 `recipient` ,
 `event_type` FROM `scheduled_alert` WHERE `created_on` > ? AND STATUS = ? GROUP BY ?,
 ... HAVING `num_alerts` > ?
