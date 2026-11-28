package com.foodmap.user.entity;

import com.foodmap.user.domain.AdminInviteStatus;
import lombok.Data;
import lombok.ToString;

/** 发码记录的查询投影，仅选择管理页需要的字段。 */
@Data
public class AdminInviteRecord {
    private long id;
    @ToString.Exclude
    private String boundEmail;
    private String registeredUsername;
    private Long registeredUserId;
    private AdminInviteStatus status;
    private long createdMicros;
    private long expiryMicros;
}
