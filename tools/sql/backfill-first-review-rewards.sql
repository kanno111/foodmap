-- 在已完成 V59 的数据库中，通过同一个交互式 mysql 会话执行。
-- 默认预检。写入前设置会话变量 first_review_rewards_apply=1、first_review_rewards_admin_id
-- 以及 first_review_rewards_before（仅处理发布时间严格早于该时间的旧公告）。
-- 脚本结束后核对结果，再在同一会话执行 COMMIT 或 ROLLBACK。
DROP TEMPORARY TABLE IF EXISTS first_review_reward_candidates;
CREATE TEMPORARY TABLE first_review_reward_candidates (
    announcement_id BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (announcement_id)
) ENGINE=InnoDB;

START TRANSACTION;

INSERT INTO first_review_reward_candidates (announcement_id)
SELECT id
FROM notification_announcements
WHERE LOCATE('支付宝口令红包', title) > 0
  AND (@first_review_rewards_before IS NULL OR published_at < @first_review_rewards_before);

-- 全体公告会显示在预检中，但只有指定公告拥有可用于补录的原始名单。
SELECT a.id AS announcement_id, a.title, a.audience, a.status, a.published_at,
       COUNT(r.user_id) AS recipients,
       SUM(CASE WHEN r.user_id IS NOT NULL AND w.id IS NULL THEN 1 ELSE 0 END) AS pending,
       SUM(CASE WHEN w.reward_type = 'FIRST_REVIEW' THEN 1 ELSE 0 END) AS registered,
       SUM(CASE WHEN w.id IS NOT NULL AND w.reward_type != 'FIRST_REVIEW' THEN 1 ELSE 0 END) AS conflicts
FROM first_review_reward_candidates c
JOIN notification_announcements a ON a.id = c.announcement_id
LEFT JOIN notification_announcement_recipients r ON r.announcement_id = a.id AND a.audience = 'TARGETED'
LEFT JOIN user_manual_reward_records w ON w.source_announcement_id = a.id AND w.user_id = r.user_id
GROUP BY a.id, a.title, a.audience, a.status, a.published_at
ORDER BY a.published_at, a.id;

-- 同一用户收到不同公告时保留来源，单独列出供操作者核对。
SELECT r.user_id, COUNT(*) AS matching_announcements
FROM first_review_reward_candidates c
JOIN notification_announcements a ON a.id = c.announcement_id AND a.audience = 'TARGETED'
JOIN notification_announcement_recipients r ON r.announcement_id = a.id
GROUP BY r.user_id
HAVING COUNT(*) > 1
ORDER BY r.user_id;

SET @first_review_rewards_conflicts = (
    SELECT COUNT(*)
    FROM first_review_reward_candidates c
    JOIN notification_announcements a ON a.id = c.announcement_id AND a.audience = 'TARGETED'
    JOIN notification_announcement_recipients r ON r.announcement_id = a.id
    JOIN user_manual_reward_records w ON w.source_announcement_id = a.id AND w.user_id = r.user_id
    WHERE w.reward_type != 'FIRST_REVIEW'
);
SET @first_review_rewards_admin_valid = EXISTS (
    SELECT 1
    FROM users u
    JOIN user_roles ur ON ur.user_id = u.id
    JOIN roles rl ON rl.id = ur.role_id
    WHERE u.id = @first_review_rewards_admin_id AND u.status = 'ACTIVE' AND rl.code = 'ROLE_ADMIN'
);
SET @first_review_rewards_allowed = COALESCE(@first_review_rewards_apply, 0) = 1
    AND @first_review_rewards_before IS NOT NULL
    AND @first_review_rewards_admin_valid
    AND @first_review_rewards_conflicts = 0;

INSERT INTO user_manual_reward_records
    (user_id, reward_type, reward_note, source_announcement_id, recorded_by)
SELECT r.user_id, 'FIRST_REVIEW', NULL, a.id, @first_review_rewards_admin_id
FROM first_review_reward_candidates c
JOIN notification_announcements a ON a.id = c.announcement_id AND a.audience = 'TARGETED'
JOIN notification_announcement_recipients r ON r.announcement_id = a.id
LEFT JOIN user_manual_reward_records w ON w.source_announcement_id = a.id AND w.user_id = r.user_id
WHERE @first_review_rewards_allowed AND w.id IS NULL
ORDER BY a.id, r.user_id;

SELECT ROW_COUNT() AS inserted_uncommitted,
       @first_review_rewards_allowed AS write_enabled,
       @first_review_rewards_conflicts AS conflicts;
SET @first_review_rewards_apply = 0;
