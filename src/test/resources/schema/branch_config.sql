CREATE TABLE `branch_config` (
  `id` char(36) NOT NULL,
  `branch_id` char(36) NOT NULL,
  `name` varchar(255) NOT NULL,
  `value` text,
  `created_by` char(36) NOT NULL DEFAULT '1',
  `created_on` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `deleted_by` char(36) DEFAULT NULL,
  `deleted_on` timestamp NULL DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `branch_config__branch_id_name_idx` (`branch_id`,`name`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
