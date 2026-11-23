ALTER TABLE notification_announcements
    DROP CHECK chk_announcement_lifecycle,
    MODIFY COLUMN status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PUBLISHED'
        COMMENT '发布和撤回各区分全体及指定公告',
    ADD COLUMN audience VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ALL'
        COMMENT 'ALL全体用户、TARGETED指定用户',
    ADD CONSTRAINT chk_announcement_audience CHECK (
        (CAST(audience AS BINARY) = 'ALL' AND CAST(status AS BINARY) IN ('PUBLISHED', 'WITHDRAWN'))
        OR (CAST(audience AS BINARY) = 'TARGETED'
            AND CAST(status AS BINARY) IN ('TARGETED_PUBLISHED', 'TARGETED_WITHDRAWN'))),
    ADD CONSTRAINT chk_announcement_lifecycle CHECK (
        (CAST(status AS BINARY) IN ('PUBLISHED', 'TARGETED_PUBLISHED')
            AND withdrawn_by IS NULL AND withdrawn_at IS NULL)
        OR (CAST(status AS BINARY) IN ('WITHDRAWN', 'TARGETED_WITHDRAWN')
            AND withdrawn_by IS NOT NULL AND withdrawn_by BETWEEN 1 AND 9223372036854775807
            AND withdrawn_at IS NOT NULL AND withdrawn_at >= published_at)),
    COMMENT = '用户公告';

CREATE TABLE notification_announcement_recipients (
    announcement_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL COMMENT '收件人逻辑ID',
    PRIMARY KEY (announcement_id, user_id),
    CONSTRAINT fk_announcement_recipient_announcement FOREIGN KEY (announcement_id)
        REFERENCES notification_announcements(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT chk_announcement_recipient_user CHECK (user_id BETWEEN 1 AND 9223372036854775807)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='指定用户公告的收件范围';
