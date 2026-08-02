CREATE TABLE `customer_membership` (
  `id` char(36) NOT NULL,
  `customer_id` char(36) NOT NULL,
  `store_id` char(36) NOT NULL,
  `account_number` varchar(45) DEFAULT NULL,
  `service_start` datetime DEFAULT NULL,
  `service_end` datetime DEFAULT NULL,
  `created_on` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `customer_membership__customer_id_idx` (`customer_id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
