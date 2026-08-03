CREATE TABLE `deleted_member_branch_relationship` (
  `id` char(36) NOT NULL,
  `identifier` varchar(255) NOT NULL,
  `branch_id` char(36) NOT NULL,
  `member_id` char(36) NOT NULL,
  `effective_on` datetime DEFAULT NULL,
  `deleted_on` datetime DEFAULT NULL,
  `created_on` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
