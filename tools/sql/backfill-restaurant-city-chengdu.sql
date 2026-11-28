-- 在已应用 V60 的目标库中，用同一个新开的 mysql 会话执行。
-- 默认预检。写入前同时设置 restaurant_city_backfill_through_id 和 restaurant_city_backfill_apply=1。
-- 核对结果后在同一会话手动 COMMIT 或 ROLLBACK。
START TRANSACTION;

SET @restaurant_city_backfill_scope = COALESCE(
    @restaurant_city_backfill_through_id,
    (SELECT MAX(id) FROM restaurants)
);
SET @restaurant_city_backfill_write = COALESCE(@restaurant_city_backfill_apply, 0) = 1
    AND COALESCE(@restaurant_city_backfill_through_id, 0) > 0;

SELECT DATABASE() AS database_name,
       @restaurant_city_backfill_scope AS through_id,
       COUNT(*) AS total,
       COUNT(CASE WHEN city_name IS NULL THEN 1 END) AS pending,
       COUNT(CASE WHEN city_name = '成都市' THEN 1 END) AS already_chengdu,
       COUNT(CASE WHEN city_name <> '成都市' THEN 1 END) AS other_city
FROM restaurants
WHERE id <= @restaurant_city_backfill_scope;

UPDATE restaurants
SET city_name = '成都市'
WHERE @restaurant_city_backfill_write = 1
  AND id <= @restaurant_city_backfill_scope
  AND city_name IS NULL;

SELECT ROW_COUNT() AS updated_uncommitted,
       @restaurant_city_backfill_write AS write_enabled;

SELECT COUNT(*) AS remaining_without_city
FROM restaurants
WHERE id <= @restaurant_city_backfill_scope
  AND city_name IS NULL;

SET @restaurant_city_backfill_apply = 0;
