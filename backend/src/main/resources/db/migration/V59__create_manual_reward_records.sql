CREATE TABLE user_manual_reward_records (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    user_id BIGINT UNSIGNED NOT NULL,
    reward_type VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    reward_note VARCHAR(300) NULL,
    source_announcement_id BIGINT UNSIGNED NOT NULL COMMENT '公告模块逻辑ID',
    recorded_by BIGINT UNSIGNED NOT NULL COMMENT '登记人或历史补录操作人',
    recorded_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_manual_reward_announcement_user (source_announcement_id, user_id),
    KEY idx_manual_reward_user_time (user_id, recorded_at DESC, id DESC),
    CONSTRAINT chk_manual_reward_ids CHECK (
        user_id BETWEEN 1 AND 9223372036854775807
        AND source_announcement_id BETWEEN 1 AND 9223372036854775807
        AND recorded_by BETWEEN 1 AND 9223372036854775807),
    CONSTRAINT chk_manual_reward_type CHECK (
        CAST(reward_type AS BINARY) IN ('FIRST_REVIEW', 'REGISTRATION_INVITE', 'OTHER')),
    CONSTRAINT chk_manual_reward_note CHECK (
        (reward_note IS NULL AND reward_type != 'OTHER')
        OR (reward_note IS NOT NULL AND REGEXP_LIKE(reward_note, '[^[:space:]]')))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户人工奖励登记';
