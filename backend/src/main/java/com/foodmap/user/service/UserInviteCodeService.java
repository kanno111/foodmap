package com.foodmap.user.service;

import com.foodmap.common.exception.BusinessException;
import com.foodmap.user.domain.AdminInviteStatus;
import com.foodmap.user.dto.AdminInviteCreateRequest;
import com.foodmap.user.dto.AdminInviteQuery;
import com.foodmap.user.entity.AdminInviteCodeEntity;
import com.foodmap.user.exception.UserErrorCode;
import com.foodmap.user.mapper.UserInviteCodeMapper;
import com.foodmap.user.policy.UserInviteCodeGenerator;
import com.foodmap.user.policy.UserRegistrationPolicy;
import com.foodmap.user.vo.UserInviteViews;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class UserInviteCodeService {
    private static final int DEFAULT_VALID_DAYS = 7;
    private static final int MAX_ATTEMPTS = 3;
    private static final int PAGE_SIZE = 20;
    private static final Pattern REGISTRATION_CODE = Pattern.compile("[0-9A-HJKMNP-TV-Z]{16}|[0-9A-HJKMNP-TV-Z]{26}");

    private final UserInviteCodeMapper invites;
    private final UserInviteCodeGenerator generator;
    private final UserRegistrationPolicy registrationPolicy;

    @Transactional
    public UserInviteViews.AdminCode createAdminCode(long adminId, AdminInviteCreateRequest request) {
        AdminInviteCodeEntity invite = new AdminInviteCodeEntity();
        invite.setIssuedByAdminId(adminId);
        if (request.getEmail() != null && !request.getEmail().isBlank()) {
            invite.setBoundEmail(registrationPolicy.normalizePersonalEmail(request.getEmail()));
        }
        invite.setValidDays(Objects.requireNonNullElse(request.getValidDays(), DEFAULT_VALID_DAYS));

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String code = generator.newAdminCode();
            invite.setCodeHash(generator.hash(code));
            try {
                if (invites.insertAdminCode(invite) != 1 || invite.getId() == null) {
                    throw new IllegalStateException("Invite insert did not return a generated identifier");
                }
                // 发码和核销共用数据库时钟，响应转换使用时间戳以避开 JDBC 会话时区差异。
                Instant expiry = fromMicros(invites.findAdminExpiryMicros(invite.getId()));
                return new UserInviteViews.AdminCode(invite.getId().toString(), code, expiry);
            } catch (DuplicateKeyException collision) {
                // 管理员码没有归属人，唯一键冲突时重新生成随机码。
            }
        }
        throw new BusinessException(UserErrorCode.INVITE_CODE_UNAVAILABLE);
    }

    public UserInviteViews.AdminPage listAdminCodes(AdminInviteStatus status, int page) {
        var rows = invites.pageAdminCodes(status, PAGE_SIZE + 1, (page - 1) * PAGE_SIZE);
        var records = rows.stream().limit(PAGE_SIZE)
                .map(row -> new UserInviteViews.AdminRecord(
                        Long.toString(row.getId()), row.getBoundEmail(), row.getRegisteredUsername(), row.getStatus(),
                        fromMicros(row.getCreatedMicros()), fromMicros(row.getExpiryMicros())))
                .toList();
        return new UserInviteViews.AdminPage(records, page < AdminInviteQuery.MAX_PAGE && rows.size() > PAGE_SIZE);
    }

    public void revokeAdminCode(long id) {
        if (invites.revokeAdminCode(id) == 1) {
            return;
        }
        var status = invites.findAdminStatus(id)
                .orElseThrow(() -> new BusinessException(UserErrorCode.INVITE_NOT_FOUND));
        if (status != AdminInviteStatus.REVOKED) {
            throw new BusinessException(UserErrorCode.INVITE_NOT_REVOCABLE);
        }
    }

    private static Instant fromMicros(long micros) {
        return Instant.EPOCH.plus(micros, ChronoUnit.MICROS);
    }

    public UserInviteViews.ReferralCode getReferralCode(long userId) {
        return new UserInviteViews.ReferralCode(invites.findReferralCode(userId).orElse(null));
    }

    RegistrationInvite validateRegistration(String email, String submittedCode) {
        String normalized = registrationPolicy.normalizeAccountEmail(email);
        boolean campus = registrationPolicy.isCampusEmail(normalized);
        if (submittedCode == null || submittedCode.isBlank()) {
            if (!campus) {
                throw new BusinessException(UserErrorCode.CAMPUS_EMAIL_REQUIRED);
            }
            return new RegistrationInvite(normalized, null, false);
        }

        String code = submittedCode.strip().toUpperCase(Locale.ROOT);
        if (!REGISTRATION_CODE.matcher(code).matches() || code.length() != (campus ? 16 : 26)) {
            throw new BusinessException(UserErrorCode.INVITE_CODE_INVALID);
        }
        byte[] hash = generator.hash(code);
        long id = (campus ? invites.findReferralId(hash) : invites.findActiveAdminId(hash, normalized))
                .orElseThrow(() -> new BusinessException(UserErrorCode.INVITE_CODE_INVALID));
        return new RegistrationInvite(normalized, id, !campus);
    }

    /** 在创建账号的事务内重检并占用校友码，避免并发注册和撤销绕过发送时的校验。 */
    void consumeForRegistration(RegistrationInvite invite) {
        if (invite.adminCode() && invites.consumeAdminCode(invite.codeId(), invite.email()) != 1) {
            throw new BusinessException(UserErrorCode.INVITE_CODE_INVALID);
        }
    }

    void recordRegistration(long userId, RegistrationInvite invite) {
        if (invite.codeId() != null && invites.recordRedemption(userId, invite.codeId()) != 1) {
            throw new IllegalStateException("Invite redemption was not inserted");
        }
    }

    record RegistrationInvite(String email, Long codeId, boolean adminCode) {
    }

    /** 独立执行读写，唯一键冲突后重新读，可看到另一个请求刚提交的长期码。 */
    public UserInviteViews.ReferralCode getOrCreateReferralCode(long userId) {
        var existing = invites.findReferralCode(userId);
        if (existing.isPresent()) {
            return new UserInviteViews.ReferralCode(existing.get());
        }

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String code = generator.newReferralCode();
            try {
                if (invites.insertReferralCode(userId, code, generator.hash(code)) != 1) {
                    throw new IllegalStateException("Referral code was not inserted");
                }
                return new UserInviteViews.ReferralCode(code);
            } catch (DuplicateKeyException collision) {
                // 查到本人的码说明并发领取已成功，否则是邀请码摘要冲突，换码重试。
                existing = invites.findReferralCode(userId);
                if (existing.isPresent()) {
                    return new UserInviteViews.ReferralCode(existing.get());
                }
            }
        }
        throw new BusinessException(UserErrorCode.INVITE_CODE_UNAVAILABLE);
    }
}
