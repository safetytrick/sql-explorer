CREATE TABLE `notif_criteria_met` (
  `id` char(36) NOT NULL,
  `notif_criteria_id` char(36) NOT NULL,
  `customer_id` char(36) NOT NULL,
  `store_id` char(36) DEFAULT NULL,
  `created_on` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `notif_criteria_met__criteria_idx` (`notif_criteria_id`),
  KEY `notif_criteria_met__customer_id_idx` (`customer_id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
