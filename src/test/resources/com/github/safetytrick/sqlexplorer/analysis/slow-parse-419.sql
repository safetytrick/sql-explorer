-- 419d7d9e16908713b9515c7a1954318d
SELECT COUNT ( `id` ) AS `num_notifications` ,
 `visit_id` ,
 `destination_master_id` ,
 `store_id` ,
 `recipient` ,
 `event_type` FROM `scheduled_notification` WHERE `created_on` > ? AND STATUS = ? GROUP BY ?,
 ... HAVING `num_notifications` > ?
