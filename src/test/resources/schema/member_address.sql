CREATE TABLE `member_address` (
  `id` char(36) NOT NULL,
  `member_id` char(36) NOT NULL,
  `street_address` varchar(255) DEFAULT NULL,
  `city` varchar(255) DEFAULT NULL,
  `state` varchar(45) DEFAULT NULL,
  `zip` varchar(45) DEFAULT NULL,
  `created_on` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `member_address__member_id_idx` (`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
