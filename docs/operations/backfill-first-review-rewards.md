# 补录首次点评奖励

完成 V59 迁移后，用受控的数据库会话运行[补录脚本](../../tools/sql/backfill-first-review-rewards.sql)。来源是标题包含「支付宝口令红包」的旧指定公告，使用原收件人名单，包含已撤回的指定公告。登记时间为补录执行时间，发出的原通知仍可按公告 ID 核对。

1. 备份目标数据库。在新的交互式 mysql 会话中确认数据库和时区。用 `SET @first_review_rewards_before = '实际截止时间';` 固定纳入范围，取发布时间严格早于该时间的记录。
2. 用 `SOURCE /实际路径/backfill-first-review-rewards.sql` 预检。核对每条公告的待写人数、已登记人数、冲突，以及同一用户出现在多条公告中的情况。全体公告只有标题和范围，缺少逐人名单。输出只含公告元数据和用户 ID。
3. 在同一会话执行 `ROLLBACK;` 结束预检事务。将 `@first_review_rewards_admin_id` 设置为实际操作管理员 ID，再执行 `SET @first_review_rewards_apply = 1;` 并重新执行脚本。有奖励类型冲突时跳过整批写入。
4. 核对 `inserted_uncommitted` 和 `write_enabled`，确认新增人数后执行 `COMMIT;`，有出入则执行 `ROLLBACK;`。再运行预检确认已登记人数。脚本在预检模式结束后也应执行 `ROLLBACK;`。

同一公告和收件人只登记一次。脚本只写奖励记录，通知发送走原公告流程。
