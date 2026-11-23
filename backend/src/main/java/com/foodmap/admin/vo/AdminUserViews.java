package com.foodmap.admin.vo;

import java.time.Instant;
import java.util.List;
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
        private Instant mutedAt;
        private boolean canModerate;
    }

    public record Mute(String id, Instant mutedAt) {}

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
