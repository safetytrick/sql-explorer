CREATE TABLE `member_identifier` (
  `id` char(36) NOT NULL,
  `identifier` varchar(255) NOT NULL,
  `branch_id` char(36) NOT NULL,
  `member_id` char(36) NOT NULL,
  `type` varchar(45) DEFAULT NULL,
  `created_on` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `member_identifier__member_id_idx` (`member_id`),
  KEY `member_identifier__identifier_idx` (`identifier`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
