package com.foodmap.user.dto;

import lombok.Data;

/** 管理目录只读取用户名、标识和头像是否可用。 */
@Data
public class UserDirectoryEntry {
    private long id;
    private String username;
    private boolean hasAvatar;
}
