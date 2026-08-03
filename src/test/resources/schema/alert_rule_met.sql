CREATE TABLE `alert_rule_met` (
  `id` char(36) NOT NULL,
  `alert_rule_id` char(36) NOT NULL,
  `member_id` char(36) NOT NULL,
  `branch_id` char(36) DEFAULT NULL,
  `created_on` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `alert_rule_met__rule_idx` (`alert_rule_id`),
  KEY `alert_rule_met__member_id_idx` (`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
