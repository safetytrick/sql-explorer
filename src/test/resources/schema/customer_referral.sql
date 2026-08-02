CREATE TABLE `customer_referral` (
  `id` char(36) NOT NULL,
  `customer_id` char(36) NOT NULL,
  `managing_store_id` char(36) DEFAULT NULL,
  `referring_store_id` char(36) DEFAULT NULL,
  `coordinating_store_id` char(36) DEFAULT NULL,
  `managing_store_status` varchar(45) DEFAULT NULL,
  `created_on` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `customer_referral__customer_id_idx` (`customer_id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
