CREATE TABLE `member_referral` (
  `id` char(36) NOT NULL,
  `member_id` char(36) NOT NULL,
  `managing_branch_id` char(36) DEFAULT NULL,
  `referring_branch_id` char(36) DEFAULT NULL,
  `coordinating_branch_id` char(36) DEFAULT NULL,
  `managing_branch_status` varchar(45) DEFAULT NULL,
  `created_on` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `member_referral__member_id_idx` (`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
