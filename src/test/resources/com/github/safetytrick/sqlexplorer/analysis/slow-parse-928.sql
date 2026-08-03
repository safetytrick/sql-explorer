-- fixture: slow-parse-928
SELECT `pn` . `first_name` ,
 `pn` . `last_name` ,
 `pa` . `street_address` ,
 `pa` . `city` ,
 `pa` . `state` ,
 `pa` . `zip` ,
 `pi` . `date_of_signup` ,
 `f` . `name` ,
 `dcv` . `checkin_date` ,
 `dcv` . `id` AS `SESSIONID` ,
 `dcv` . `member_id` FROM `tdb` . `member_name` `pn` LEFT JOIN `tdb` . `member_address` `pa` ON `pn` . `member_id` = `pa` . `member_id` LEFT JOIN `tdb` . `member_ident` `pi` ON `pn` . `member_id` = `pi` . `member_id` LEFT JOIN `tdb` . `deleted_member_session` `dcv` ON `pn` . `member_id` = `dcv` . `member_id` LEFT JOIN `tdb` . `branch` `f` ON `f` . `id` = `dcv` . `branch_id` WHERE `pn` . `first_name` LIKE ? AND `pn` . `last_name` LIKE ? AND `pi` . `date_of_signup` = ? AND `main_class` IN (...) ORDER BY `checkin_date` DESC LIMIT ?
