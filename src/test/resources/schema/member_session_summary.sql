CREATE VIEW `member_session_summary` AS
SELECT `ms`.*, `b`.`name` AS `branch_name`,
       CONCAT(`b`.`name`, ' / ', `ms`.`session_type`) AS `label`
FROM `member_session` `ms`
JOIN `branch` `b` ON `ms`.`branch_id` = `b`.`id`
