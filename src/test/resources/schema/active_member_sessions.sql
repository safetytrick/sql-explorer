CREATE VIEW `active_member_sessions` AS
SELECT `ms`.`id`, `ms`.`member_id`, `ms`.`checkin_date`, `b`.`name`
FROM `member_session` `ms`
JOIN `branch` `b` ON `ms`.`branch_id` = `b`.`id`
WHERE `ms`.`checkout_date` IS NULL
