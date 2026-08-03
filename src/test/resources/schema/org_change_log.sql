CREATE TABLE `org_change_log` (
  `id` char(36) NOT NULL,
  `org_id` char(36) NOT NULL,
  `created_on` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `org_change_log__org_id_idx` (`org_id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
