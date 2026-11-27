package com.foodmap.user.dto;

import com.foodmap.user.domain.AdminInviteStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class AdminInviteQuery {
    public static final int MAX_PAGE = 10_000;

    @Min(1)
    @Max(MAX_PAGE)
    private int page = 1;

    private AdminInviteStatus status;
}
