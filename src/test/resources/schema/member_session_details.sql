CREATE TABLE `member_session_details` (
  `id` char(36) NOT NULL,
  `member_id` char(36) NOT NULL,
  `created_on` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `member_session_details__member_id_idx` (`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
