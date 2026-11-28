-- 兼容上一次ALTER已完成但发布中断的重试。既有商户的城市归属留待核对。
SET @restaurant_city_ddl = IF(
    EXISTS (SELECT 1 FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'restaurants' AND column_name = 'city_name'),
    'DO 0',
    'ALTER TABLE restaurants ADD COLUMN city_name VARCHAR(32) NULL COMMENT ''地级行政区或直辖市标准全称，未确认时为空'' AFTER address'
);
PREPARE restaurant_city_change FROM @restaurant_city_ddl;
EXECUTE restaurant_city_change;
DEALLOCATE PREPARE restaurant_city_change;
