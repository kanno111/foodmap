package com.foodmap.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;

/**
 * 发送邮箱验证码的请求 DTO。
 *
 * <p>这里只验证基础邮箱格式。注册资格、邀请码和账号绑定关系由 Service 校验。</p>
 */
@Data
public class EmailCodeRequest {

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    @Size(max = 254, message = "邮箱最多254个字符")
    private String email;

    @Size(max = 64, message = "邀请码最多64个字符")
    @ToString.Exclude
    private String inviteCode;
}
