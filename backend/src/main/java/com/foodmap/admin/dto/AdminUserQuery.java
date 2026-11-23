package com.foodmap.admin.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AdminUserQuery {
    @NotNull
    @Size(max = 32)
    private String keyword = "";

    @Min(1)
    @Max(1_000_000)
    private int page = 1;

    @Min(1)
    @Max(50)
    private int pageSize = 20;
}
