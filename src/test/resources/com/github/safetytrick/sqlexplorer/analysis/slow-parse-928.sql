-- 9284a0fd9d9aa2745149eaba7c1b6b3c
SELECT `pn` . `first_name` ,
 `pn` . `last_name` ,
 `pa` . `street_address` ,
 `pa` . `city` ,
 `pa` . `state` ,
 `pa` . `zip` ,
 `pi` . `date_of_birth` ,
 `f` . `name` ,
 `dpv` . `checkin_date` ,
 `dpv` . `id` AS `VISITID` ,
 `dpv` . `customer_id` FROM `tdb` . `customer_name` `pn` LEFT JOIN `tdb` . `customer_address` `pa` ON `pn` . `customer_id` = `pa` . `customer_id` LEFT JOIN `tdb` . `customer_ident` `pi` ON `pn` . `customer_id` = `pi` . `customer_id` LEFT JOIN `tdb` . `deleted_customer_visit` `dpv` ON `pn` . `customer_id` = `dpv` . `customer_id` LEFT JOIN `tdb` . `store` `f` ON `f` . `id` = `dpv` . `store_id` WHERE `pn` . `first_name` LIKE ? AND `pn` . `last_name` LIKE ? AND `pi` . `date_of_birth` = ? AND `major_class` IN (...) ORDER BY `checkin_date` DESC LIMIT ?
