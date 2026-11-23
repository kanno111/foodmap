package com.foodmap.notification.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AnnouncementRecipientQuery {
    @Size(max = 64)
    private String keyword = "";

    @Min(1)
    @Max(1_000_000)
    private int page = 1;
}
