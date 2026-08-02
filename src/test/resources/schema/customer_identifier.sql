CREATE TABLE `customer_identifier` (
  `id` char(36) NOT NULL,
  `identifier` varchar(255) NOT NULL,
  `store_id` char(36) NOT NULL,
  `customer_id` char(36) NOT NULL,
  `type` varchar(45) DEFAULT NULL,
  `created_on` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `customer_identifier__customer_id_idx` (`customer_id`),
  KEY `customer_identifier__identifier_idx` (`identifier`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
