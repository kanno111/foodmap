package com.foodmap.user.policy;

import com.foodmap.common.exception.BusinessException;
import com.foodmap.user.config.UserRegistrationProperties;
import com.foodmap.user.exception.UserErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 集中执行账号邮箱、用户名与密码规则。邀请码校验负责个人邮箱的注册资格。
 */
@Service
@RequiredArgsConstructor
public class UserRegistrationPolicy {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("[\\p{IsHan}a-zA-Z0-9_]+");
    private static final Pattern PASSWORD_PATTERN = Pattern.compile("[\\x21-\\x7e]+");

    private final UserRegistrationProperties properties;

    /**
     * 规范化并精确校验校园邮箱域名。
     *
     * @param email 用户提交的完整邮箱
     * @return 去除首尾空白并转换为小写的邮箱
     */
    public String normalizeCampusEmail(String email) {
        String normalized = normalizeEmail(email);
        if (!hasEmailShape(normalized) || !isCampusEmail(normalized)) {
            throw new BusinessException(UserErrorCode.CAMPUS_EMAIL_REQUIRED);
        }
        return normalized;
    }

    /** 校友邀请绑定格式已校验的个人邮箱，沿用校园账号的大小写规范。 */
    public String normalizePersonalEmail(String email) {
        String normalized = normalizeEmail(email);
        if (!hasEmailShape(normalized) || isCampusEmail(normalized)) {
            throw new BusinessException(UserErrorCode.PERSONAL_EMAIL_REQUIRED);
        }
        return normalized;
    }

    public String normalizeAccountEmail(String email) {
        String normalized = normalizeEmail(email);
        if (!hasEmailShape(normalized)) {
            throw new BusinessException(UserErrorCode.EMAIL_INVALID);
        }
        return normalized;
    }

    /** 调用方已完成邮箱规范化及格式校验。 */
    public boolean isCampusEmail(String normalizedEmail) {
        String domain = normalizedEmail.substring(normalizedEmail.lastIndexOf('@') + 1);
        return properties.getAllowedEmailDomains().stream()
                .map(String::trim)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(domain::equals);
    }

    private static boolean hasEmailShape(String email) {
        int separator = email.indexOf('@');
        return email.length() <= 254 && separator > 0 && separator == email.lastIndexOf('@')
                && separator < email.length() - 1 && email.chars().noneMatch(Character::isWhitespace);
    }

    private static String normalizeEmail(String email) {
        Objects.requireNonNull(email, "email must not be null");
        return email.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 规范化用户名并限制为文字、数字和下划线。
     *
     * <p>长度按 Unicode 字符计算，使中文用户名不会因 UTF-16 代理对而被错误计数。</p>
     *
     * @param username 用户提交的用户名
     * @return 去除首尾空白后的用户名
     */
    public String normalizeUsername(String username) {
        Objects.requireNonNull(username, "username must not be null");
        String normalized = username.trim();
        int length = normalized.codePointCount(0, normalized.length());
        if (length < properties.getUsernameMinLength()
                || length > properties.getUsernameMaxLength()
                || !USERNAME_PATTERN.matcher(normalized).matches()) {
            throw new BusinessException(UserErrorCode.USERNAME_INVALID,
                    "用户名需为" + properties.getUsernameMinLength() + "–" + properties.getUsernameMaxLength()
                            + "位，仅支持中文、英文字母、数字和下划线，区分大小写");
        }
        return normalized;
    }

    /**
     * 校验4–32位英文可打印字符，不强制字符组合。
     *
     * <p>不做 trim，含空格、中文或控制字符的输入直接拒绝。ASCII最长32字节，小于BCrypt
     * 的72字节上限。新规则只用于注册与重置，不追溯限制历史密码登录。</p>
     *
     * @param password 用户提交的明文密码，仅在当前请求内短暂存在
     */
    public void validatePassword(String password) {
        Objects.requireNonNull(password, "password must not be null");
        int characterCount = password.codePointCount(0, password.length());
        if (characterCount < properties.getPasswordMinLength()
                || characterCount > properties.getPasswordMaxLength()
                || !PASSWORD_PATTERN.matcher(password).matches()) {
            throw new BusinessException(UserErrorCode.PASSWORD_WEAK,
                    "密码需为" + properties.getPasswordMinLength() + "–" + properties.getPasswordMaxLength()
                            + "位，仅支持英文字母、数字和英文符号，不允许中文或空格");
        }
    }
}
