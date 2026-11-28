# 回填历史商户所属城市

已确认本次回填范围内的历史商户都在成都市。完成 V60 并备份目标数据库后，用新的交互式 mysql 会话运行[回填脚本](../../tools/sql/backfill-restaurant-city-chengdu.sql)。请在对外开放新增外地店铺前记录预检给出的 `through_id`，用它固定历史范围。

1. 选中目标数据库，运行 `SOURCE /实际路径/backfill-restaurant-city-chengdu.sql` 预检。核对 `database_name`、`through_id`、`pending` 和 `other_city`。预检结果应为 `write_enabled=0` 和 `updated_uncommitted=0`，然后执行 `ROLLBACK;`。
2. 在同一会话执行 `SET @restaurant_city_backfill_through_id = 实际截止ID;` 和 `SET @restaurant_city_backfill_apply = 1;`，再运行脚本。只更新截止 ID 内 `city_name IS NULL` 的商户，包括该范围内的已删除历史商户。已有城市和更晚新增的商户保留原值。
3. 核对 `updated_uncommitted` 符合预期，且 `remaining_without_city` 为 `0` 后执行 `COMMIT;`。有出入就执行 `ROLLBACK;`。可用相同截止 ID 再预检一次，确认 `pending` 为 `0`。

脚本只写 `restaurants.city_name`。

## 接入首页和 Elasticsearch

先完成上面的历史回填，再发布首页城市筛选。首页默认匹配 `成都市`，城市为空的历史店铺在回填前不会进入该筛选结果。

已启用 Elasticsearch 的环境，在切换应用前安排一次维护窗口。暂停店铺资料和点评写入，等待 v004 的当前版本 Outbox 清空，再停止旧版同步消费。保持别名指向已同步的 v004，先预检，再为同一个数据库执行正式升级。MySQL 和 ES 均通过本机回环访问，MySQL 密码从环境变量 `FOODMAP_CITY_DB_PASSWORD` 读取，正式升级账号须能对商户表与 Outbox 取得读锁。

```sh
python backend/tools/upgrade_restaurant_city_index.py --mysql-user DB_USER --database DB_NAME
python backend/tools/upgrade_restaurant_city_index.py --mysql-user DB_USER --database DB_NAME --apply
```

工具从 MySQL 读取城市，保留原 ES 文档 ID、原版本及删除墓碑。候选 v005 的文档核对完才切换别名，旧 v004 保留。切换后以新版应用恢复同步消费和业务写入。在旧索引缺少城市字段时新版城市查询降级到 MySQL，等待索引和业务版本就绪后验证 ES 与 MySQL 的城市结果一致。只使用 MySQL 的部署完成 V60 和回填后即可使用城市筛选。
