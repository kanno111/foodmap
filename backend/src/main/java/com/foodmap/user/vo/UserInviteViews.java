package com.foodmap.user.vo;

import lombok.ToString;
import lombok.Value;

import java.time.Instant;

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
}
