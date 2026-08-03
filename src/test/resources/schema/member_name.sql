CREATE TABLE `member_name` (
  `id` char(36) NOT NULL,
  `member_id` char(36) NOT NULL,
  `first_name` varchar(255) DEFAULT NULL,
  `last_name` varchar(255) DEFAULT NULL,
  `created_on` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `member_name__member_id_idx` (`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
