-- 928e0ca25ecd000fa18f7da2357f27c5
SELECT `pn` . `first_name` ,
 `pn` . `last_name` ,
 `pa` . `street_address` ,
 `pa` . `city` ,
 `pa` . `state` ,
 `pa` . `zip` ,
 `pi` . `date_of_birth` ,
 `f` . `name` ,
 `dcv` . `checkin_date` ,
 `dcv` . `id` AS `VISITID` ,
 `dcv` . `customer_id` FROM `tdb` . `customer_name` `pn` LEFT JOIN `tdb` . `customer_address` `pa` ON `pn` . `customer_id` = `pa` . `customer_id` LEFT JOIN `tdb` . `customer_ident` `pi` ON `pn` . `customer_id` = `pi` . `customer_id` LEFT JOIN `tdb` . `deleted_customer_visit` `dcv` ON `pn` . `customer_id` = `dcv` . `customer_id` LEFT JOIN `tdb` . `store` `f` ON `f` . `id` = `dcv` . `store_id` WHERE `pn` . `first_name` LIKE ? AND `pn` . `last_name` LIKE ? AND `pi` . `date_of_birth` = ? AND `major_class` IN (...) ORDER BY `checkin_date` DESC LIMIT ?
