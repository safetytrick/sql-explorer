CREATE TABLE `member_ident` (
  `id` char(36) NOT NULL,
  `member_id` char(36) NOT NULL,
  `date_of_signup` date DEFAULT NULL,
  `account_number` varchar(45) DEFAULT NULL,
  `created_on` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `member_ident__member_id_idx` (`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
