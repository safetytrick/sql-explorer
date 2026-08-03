CREATE TABLE `deleted_member_session` (
  `id` char(36) NOT NULL,
  `member_id` char(36) NOT NULL,
  `branch_id` char(36) NOT NULL,
  `checkin_date` datetime DEFAULT NULL,
  `checkout_date` datetime DEFAULT NULL,
  `session_type` varchar(45) DEFAULT NULL,
  `main_class` varchar(45) DEFAULT NULL,
  `deleted_on` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `deleted_member_session__member_id_idx` (`member_id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
