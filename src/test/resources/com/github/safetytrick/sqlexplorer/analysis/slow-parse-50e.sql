-- 50e3cb0397afa08753e9b31b15c8c414
SELECT COUNT ( `created_on` ),
 DATE ( `created_on` )
FROM `ccd`
 WHERE `created_on` >= ? AND `created_on` < ? GROUP BY DATE ( `created_on` ) LIMIT ?
