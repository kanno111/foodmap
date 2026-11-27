ALTER TABLE users
    MODIFY COLUMN email VARCHAR(254) NOT NULL COMMENT '规范化后的账号绑定邮箱，全局唯一',
    MODIFY COLUMN email_verified BOOLEAN NOT NULL DEFAULT FALSE COMMENT '当前绑定邮箱是否已通过验证',
    MODIFY COLUMN email_verified_at TIMESTAMP(6) NULL DEFAULT NULL COMMENT '当前绑定邮箱通过验证的时间';
