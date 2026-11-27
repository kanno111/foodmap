package com.foodmap.user.dto;

import java.time.Instant;
import lombok.Data;

/** 管理目录使用的基本信息和禁言状态。 */
@Data
public class UserDirectoryEntry {
    private long id;
    private String username;
    private boolean hasAvatar;
    private Instant mutedAt;
    private boolean canModerate;
    private Instant createdAt;
    private String status;
}
