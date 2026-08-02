CREATE TABLE `customer_feedback` (
  `id` char(36) NOT NULL,
  `customer_id` char(36) NOT NULL,
  `code` varchar(45) DEFAULT NULL,
  `created_on` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `customer_feedback__customer_id_idx` (`customer_id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
