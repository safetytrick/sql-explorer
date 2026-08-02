CREATE TABLE `report_column` (
  `id` char(36) NOT NULL,
  `report_id` char(36) NOT NULL,
  `created_by` char(36) NOT NULL,
  `created_on` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `deleted_by` char(36) DEFAULT NULL,
  `deleted_on` timestamp NULL DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `report_column__report_id_idx` (`report_id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
