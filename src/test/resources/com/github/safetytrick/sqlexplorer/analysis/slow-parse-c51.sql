-- c51cbd4afbea5026131586efbe896ec5

SELECT COUNT ( `created_on` ) ,
    DATE ( `created_on` )
FROM `ccd` WHERE `created_on` >= ? AND `created_on` < ? GROUP BY DATE ( `created_on` ) LIMIT ?
