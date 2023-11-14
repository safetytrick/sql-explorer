-- 419bf9f62616abf32fb38b2c9576ec86
SELECT COUNT ( `id` ) AS `num_notifications` ,
 `visit_id` ,
 `destination_master_id` ,
 `store_id` ,
 `recipient` ,
 `event_type` FROM `scheduled_notification` WHERE `created_on` > ? AND STATUS = ? GROUP BY ?,
 ... HAVING `num_notifications` > ?
