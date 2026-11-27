CREATE TABLE IF NOT EXISTS user_invite_codes (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    type VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '管理员一次性码或用户长期推荐码',
    code_hash BINARY(32) NOT NULL COMMENT '邀请码摘要，用于查找和唯一校验',
    public_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL DEFAULT NULL COMMENT '仅保存用户长期码的分享原文',
    issued_by_admin_id BIGINT UNSIGNED NULL DEFAULT NULL COMMENT '管理员一次性码的发码人',
    owner_user_id BIGINT UNSIGNED NULL DEFAULT NULL COMMENT '用户长期码的归属人',
    bound_email VARCHAR(254) NULL DEFAULT NULL COMMENT '管理员一次性码绑定的规范化邮箱',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at TIMESTAMP(6) NULL DEFAULT NULL,
    revoked_at TIMESTAMP(6) NULL DEFAULT NULL,
    used_at TIMESTAMP(6) NULL DEFAULT NULL COMMENT '注册事务内原子核销管理员码的时间',
    PRIMARY KEY (id),
    CONSTRAINT uk_user_invite_codes_hash UNIQUE (code_hash),
    CONSTRAINT uk_user_invite_codes_owner UNIQUE (owner_user_id),
    INDEX idx_user_invite_codes_issuer (issued_by_admin_id),
    CONSTRAINT fk_user_invite_codes_issuer FOREIGN KEY (issued_by_admin_id)
        REFERENCES users (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_user_invite_codes_owner FOREIGN KEY (owner_user_id)
        REFERENCES users (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT chk_user_invite_codes_type_fields CHECK (
        (CAST(type AS BINARY) = 'ADMIN_SINGLE_USE'
            AND public_code IS NULL AND issued_by_admin_id IS NOT NULL AND owner_user_id IS NULL
            AND bound_email IS NOT NULL AND CHAR_LENGTH(TRIM(bound_email)) > 0
            AND expires_at IS NOT NULL)
        OR (CAST(type AS BINARY) = 'USER_REFERRAL'
            AND public_code IS NOT NULL AND CHAR_LENGTH(TRIM(public_code)) > 0
            AND issued_by_admin_id IS NULL AND owner_user_id IS NOT NULL AND bound_email IS NULL
            AND expires_at IS NULL AND revoked_at IS NULL AND used_at IS NULL)
    ),
    CONSTRAINT chk_user_invite_codes_lifecycle CHECK (
        (expires_at IS NULL OR expires_at > created_at)
        AND (revoked_at IS NULL OR (revoked_at >= created_at AND used_at IS NULL))
        AND (used_at IS NULL OR (used_at >= created_at AND used_at < expires_at))
    )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户注册邀请码';

CREATE TABLE IF NOT EXISTS user_invite_redemptions (
    invitee_user_id BIGINT UNSIGNED NOT NULL COMMENT '使用邀请码成功注册的用户',
    invite_code_id BIGINT UNSIGNED NOT NULL,
    redeemed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (invitee_user_id),
    INDEX idx_user_invite_redemptions_code_time (invite_code_id, redeemed_at, invitee_user_id),
    CONSTRAINT fk_user_invite_redemptions_invitee FOREIGN KEY (invitee_user_id)
        REFERENCES users (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_user_invite_redemptions_code FOREIGN KEY (invite_code_id)
        REFERENCES user_invite_codes (id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '用户注册邀请码使用记录';
