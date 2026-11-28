package com.foodmap.admin.vo;

import java.time.Instant;
import java.util.List;
import java.math.BigDecimal;
import com.foodmap.review.dto.ReviewRecommendation;
import com.foodmap.review.vo.ReviewPageVo;
import com.foodmap.user.domain.ManualRewardType;
import lombok.AllArgsConstructor;
import lombok.Data;

public final class AdminUserViews {
    private AdminUserViews() {}

    @Data
    @AllArgsConstructor
    public static class Item {
        private String id;
        private String username;
        private boolean hasAvatar;
        private long reviewCount;
        private long approvedSubmissionCount;
        private long invitedRegistrationCount;
        private RewardSummary rewardSummary;
        private Instant mutedAt;
        private boolean canModerate;
    }

    public record Mute(String id, Instant mutedAt) {}

    public record RewardSummary(List<ManualRewardType> types, boolean hasMore) {}

    public record RegistrationSource(String kind, String inviterName) {}

    public record Profile(String id, String username, boolean hasAvatar, long reviewCount,
            long approvedSubmissionCount, long invitedRegistrationCount, Instant mutedAt,
            boolean canModerate, Instant createdAt, String status, RegistrationSource registrationSource) {}

    public record Review(String id, String restaurantName, String body, BigDecimal overallScore,
            ReviewRecommendation recommendation, String scoringStatus, Instant createdAt,
            ReviewPageVo.Scores scores, List<ReviewPageVo.Photo> photos) {}

    public record Invitee(String id, String username, boolean hasAvatar, Instant registeredAt,
            boolean profileAvailable) {}

    public record Reward(String id, ManualRewardType rewardType, String rewardNote,
            String sourceAnnouncementId, String sourceAnnouncementTitle, boolean sourceWithdrawn,
            Instant recordedAt) {}

    public record DetailPage<T>(List<T> records, long totalPages) {}

    @Data
    @AllArgsConstructor
    public static class Page {
        private List<Item> records;
        private long total;
        private int page;
        private int pageSize;
        private long totalPages;
    }
}
