CREATE TABLE `customer_visit` (
  `id` char(36) NOT NULL,
  `customer_id` char(36) NOT NULL,
  `store_id` char(36) NOT NULL,
  `checkin_date` datetime DEFAULT NULL,
  `checkout_date` datetime DEFAULT NULL,
  `visit_type` varchar(45) DEFAULT NULL,
  `major_class` varchar(45) DEFAULT NULL,
  `created_on` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `customer_visit__customer_id_idx` (`customer_id`),
  KEY `customer_visit__store_id_idx` (`store_id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
