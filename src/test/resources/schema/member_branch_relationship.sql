CREATE TABLE `member_branch_relationship` (
  `id` char(36) NOT NULL,
  `identifier` varchar(255) NOT NULL,
  `branch_id` char(36) NOT NULL,
  `member_id` char(36) NOT NULL,
  `assigning_auth` varchar(255) DEFAULT NULL,
  `effective_on` datetime DEFAULT NULL,
  `created_on` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `agent__member_id_idx` (`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
