package com.foodmap.user.entity;

import lombok.Data;
import lombok.ToString;

@Data
public class AdminInviteCodeEntity {
    private Long id;
    @ToString.Exclude
    private byte[] codeHash;
    private long issuedByAdminId;
    @ToString.Exclude
    private String boundEmail;
    private int validDays;
}
