CREATE TABLE `report` (
  `id` char(36) NOT NULL,
  `store_id` char(36) NOT NULL,
  `name` varchar(255) DEFAULT NULL,
  `created_on` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
