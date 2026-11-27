-- 原子替换类型约束，保留已有绑定邮箱和核销记录。通过旧约束名判断是否需要执行。
SET @invite_fields_ddl = IF(
    EXISTS (
        SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
        WHERE CONSTRAINT_SCHEMA = DATABASE()
            AND TABLE_NAME = 'user_invite_codes'
            AND CONSTRAINT_NAME = 'chk_user_invite_codes_type_fields'
            AND CONSTRAINT_TYPE = 'CHECK'
    ),
    'ALTER TABLE user_invite_codes
        DROP CHECK chk_user_invite_codes_type_fields,
        ADD CONSTRAINT chk_user_invite_codes_allowed_fields CHECK (
            (CAST(type AS BINARY) = ''ADMIN_SINGLE_USE''
                AND public_code IS NULL AND issued_by_admin_id IS NOT NULL AND owner_user_id IS NULL
                AND (bound_email IS NULL OR CHAR_LENGTH(TRIM(bound_email)) > 0)
                AND expires_at IS NOT NULL)
            OR (CAST(type AS BINARY) = ''USER_REFERRAL''
                AND public_code IS NOT NULL AND CHAR_LENGTH(TRIM(public_code)) > 0
                AND issued_by_admin_id IS NULL AND owner_user_id IS NOT NULL AND bound_email IS NULL
                AND expires_at IS NULL AND revoked_at IS NULL AND used_at IS NULL)
        )',
    'DO 0'
);
PREPARE invite_fields_change FROM @invite_fields_ddl;
EXECUTE invite_fields_change;
DEALLOCATE PREPARE invite_fields_change;
