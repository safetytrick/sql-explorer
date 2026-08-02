CREATE TABLE `deleted_customer_visit` (
  `id` char(36) NOT NULL,
  `customer_id` char(36) NOT NULL,
  `store_id` char(36) NOT NULL,
  `checkin_date` datetime DEFAULT NULL,
  `checkout_date` datetime DEFAULT NULL,
  `visit_type` varchar(45) DEFAULT NULL,
  `major_class` varchar(45) DEFAULT NULL,
  `deleted_on` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `deleted_customer_visit__customer_id_idx` (`customer_id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
