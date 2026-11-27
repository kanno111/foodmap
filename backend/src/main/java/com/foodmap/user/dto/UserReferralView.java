package com.foodmap.user.dto;

import java.time.Instant;

public final class UserReferralView {
    private UserReferralView() {}

    public record RegistrationSource(String kind, String inviterName) {}

    public record Invitee(long id, String username, boolean hasAvatar, Instant registeredAt,
                          boolean profileAvailable) {}
}
