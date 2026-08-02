CREATE TABLE `customer_store_relationship` (
  `id` char(36) NOT NULL,
  `identifier` varchar(255) NOT NULL,
  `store_id` char(36) NOT NULL,
  `customer_id` char(36) NOT NULL,
  `assigning_auth` varchar(255) DEFAULT NULL,
  `effective_on` datetime DEFAULT NULL,
  `created_on` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `csr__customer_id_idx` (`customer_id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
