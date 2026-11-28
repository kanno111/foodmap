package com.foodmap.user.dto;

import com.foodmap.user.domain.ManualRewardType;
import java.time.Instant;

public record ManualRewardEntry(
        long id,
        ManualRewardType rewardType,
        String rewardNote,
        long sourceAnnouncementId,
        Instant recordedAt) {
    public record SummaryCandidate(long id, long userId, ManualRewardType rewardType,
            long sourceAnnouncementId, Instant recordedAt) {}
}
