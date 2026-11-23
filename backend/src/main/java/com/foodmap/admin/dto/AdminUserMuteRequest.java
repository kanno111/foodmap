package com.foodmap.admin.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AdminUserMuteRequest {
    @NotNull
    private Boolean muted;
}
