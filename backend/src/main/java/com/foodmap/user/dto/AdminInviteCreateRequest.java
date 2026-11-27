package com.foodmap.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;

@Data
public class AdminInviteCreateRequest {
    // 支持旧发码请求。页面生成的新码默认不限制邮箱。
    @Email(message = "邮箱格式不正确")
    @Size(max = 254, message = "邮箱最多254个字符")
    @ToString.Exclude
    private String email;

    @Min(value = 1, message = "有效期至少1天")
    @Max(value = 30, message = "有效期最多30天")
    private Integer validDays;
}
