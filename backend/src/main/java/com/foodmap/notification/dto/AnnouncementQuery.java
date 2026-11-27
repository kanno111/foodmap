package com.foodmap.notification.dto;

import jakarta.validation.constraints.*;

import lombok.Data;

/** 管理列表采用有界分页，支持独立筛选状态及发送范围。 */
@Data
public class AnnouncementQuery {
    @NotNull
    @Pattern(regexp = "ALL|PUBLISHED|WITHDRAWN")
    private String status = "ALL";

    @NotNull
    @Pattern(regexp = "ANY|ALL|TARGETED")
    private String audience = "ANY";

    @Min(1)
    @Max(1000000)
    private int page = 1;

    @Min(1)
    @Max(50)
    private int pageSize = 10;
}
