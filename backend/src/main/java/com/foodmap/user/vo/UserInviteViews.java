package com.foodmap.user.vo;

import com.foodmap.user.domain.AdminInviteStatus;
import lombok.ToString;
import lombok.Value;

import java.time.Instant;
import java.util.List;

public final class UserInviteViews {
    private UserInviteViews() {
    }

    @Value
    public static class AdminCode {
        String id;
        @ToString.Exclude
        String code;
        Instant expiresAt;
    }

    @Value
    public static class ReferralCode {
        @ToString.Exclude
        String code;
    }

    @Value
    public static class AdminRecord {
        String id;
        @ToString.Exclude
        String boundEmail;
        String registeredUsername;
        AdminInviteStatus status;
        Instant createdAt;
        Instant expiresAt;
    }

    public record AdminPage(List<AdminRecord> records, boolean hasNext) {
    }
}
