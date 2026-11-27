package com.foodmap.notification.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;
import java.util.List;

/** 公告白名单响应，不暴露请求摘要、个人删除记录或内部UUID二进制。 */
public final class AnnouncementViews {
    private AnnouncementViews() {}

    @Data
    public static class Detail {
        private String id;
        private String title;
        private String content;
        private String status;
        private String audience;
        private String publishedBy;
        private Instant publishedAt;
        private String withdrawnBy;
        private Instant withdrawnAt;
        private boolean replayed;
    }

    public record Recipient(String id, String username) {}

    /** 仅管理员详情返回收件人，删除账号后保留其历史 ID。 */
    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class AdminDetail extends Detail {
        private List<Recipient> recipients;
    }

    public record RecipientPage(List<Recipient> records, boolean hasNext) {}

    @Data
    public static class Page {
        private List<Detail> records;
        private long total;
        private int page;
        private int pageSize;
        private long totalPages;
    }
}
