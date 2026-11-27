package com.foodmap.notification.dto;

import com.foodmap.user.domain.ManualRewardType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import lombok.Data;

import java.util.List;
import java.util.UUID;

/** 发布身份与时间由服务端确定，指定发布必须提供收件人。 */
@Data
public class AnnouncementPublishRequest {
    @NotNull private UUID clientRequestId;
    @NotBlank private String title;
    @NotBlank private String content;
    @Size(max = 100)
    private List<@NotBlank String> recipientUserIds;

    @Valid private RewardRegistration rewardRegistration;

    @Data
    public static class RewardRegistration {
        @NotNull private ManualRewardType type;
        @lombok.ToString.Exclude private String note;
    }
}
