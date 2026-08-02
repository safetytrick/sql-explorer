CREATE TABLE `tenant_change_log` (
  `id` char(36) NOT NULL,
  `tenant_id` char(36) NOT NULL,
  `created_on` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `tenant_change_log__tenant_id_idx` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
