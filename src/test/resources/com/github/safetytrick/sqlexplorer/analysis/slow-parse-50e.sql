-- 50e6efbb167ce33bd03a403411e4c6ad
SELECT COUNT ( `created_on` ),
 DATE ( `created_on` )
FROM `doc`
 WHERE `created_on` >= ? AND `created_on` < ? GROUP BY DATE ( `created_on` ) LIMIT ?
