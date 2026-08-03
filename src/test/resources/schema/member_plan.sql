CREATE TABLE `member_plan` (
  `id` char(36) NOT NULL,
  `member_id` char(36) NOT NULL,
  `branch_id` char(36) NOT NULL,
  `account_number` varchar(45) DEFAULT NULL,
  `service_start` datetime DEFAULT NULL,
  `service_end` datetime DEFAULT NULL,
  `created_on` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `member_plan__member_id_idx` (`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
