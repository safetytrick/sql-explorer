CREATE TABLE `store_config` (
  `id` char(36) NOT NULL,
  `store_id` char(36) NOT NULL,
  `name` varchar(255) NOT NULL,
  `value` text,
  `created_by` char(36) NOT NULL DEFAULT '1',
  `created_on` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `deleted_by` char(36) DEFAULT NULL,
  `deleted_on` timestamp NULL DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `store_config__store_id_name_idx` (`store_id`,`name`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
