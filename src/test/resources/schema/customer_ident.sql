CREATE TABLE `customer_ident` (
  `id` char(36) NOT NULL,
  `customer_id` char(36) NOT NULL,
  `date_of_birth` date DEFAULT NULL,
  `account_number` varchar(45) DEFAULT NULL,
  `created_on` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `customer_ident__customer_id_idx` (`customer_id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
