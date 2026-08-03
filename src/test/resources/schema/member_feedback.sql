CREATE TABLE `member_feedback` (
  `id` char(36) NOT NULL,
  `member_id` char(36) NOT NULL,
  `code` varchar(45) DEFAULT NULL,
  `created_on` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `member_feedback__member_id_idx` (`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
