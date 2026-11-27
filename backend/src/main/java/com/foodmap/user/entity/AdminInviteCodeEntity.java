package com.foodmap.user.entity;

import lombok.Data;
import lombok.ToString;

import java.time.Instant;

@Data
public class AdminInviteCodeEntity {
    private Long id;
    @ToString.Exclude
    private byte[] codeHash;
    private long issuedByAdminId;
    @ToString.Exclude
    private String boundEmail;
    private Instant createdAt;
    private Instant expiresAt;
}
