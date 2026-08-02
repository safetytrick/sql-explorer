-- c514677fb2aa2e500a40ab38b2970808

SELECT COUNT ( `created_on` ) ,
    DATE ( `created_on` )
FROM `doc` WHERE `created_on` >= ? AND `created_on` < ? GROUP BY DATE ( `created_on` ) LIMIT ?
