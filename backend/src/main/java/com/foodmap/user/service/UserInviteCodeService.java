package com.foodmap.user.service;

import com.foodmap.common.exception.BusinessException;
import com.foodmap.user.dto.AdminInviteCreateRequest;
import com.foodmap.user.entity.AdminInviteCodeEntity;
import com.foodmap.user.exception.UserErrorCode;
import com.foodmap.user.mapper.UserInviteCodeMapper;
import com.foodmap.user.policy.UserInviteCodeGenerator;
import com.foodmap.user.policy.UserRegistrationPolicy;
import com.foodmap.user.vo.UserInviteViews;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class UserInviteCodeService {
    private static final int DEFAULT_VALID_DAYS = 7;
    private static final int MAX_ATTEMPTS = 3;

    private final UserInviteCodeMapper invites;
    private final UserInviteCodeGenerator generator;
    private final UserRegistrationPolicy registrationPolicy;

    public UserInviteViews.AdminCode createAdminCode(long adminId, AdminInviteCreateRequest request) {
        int validDays = Objects.requireNonNullElse(request.getValidDays(), DEFAULT_VALID_DAYS);
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        AdminInviteCodeEntity invite = new AdminInviteCodeEntity();
        invite.setIssuedByAdminId(adminId);
        invite.setBoundEmail(registrationPolicy.normalizePersonalEmail(request.getEmail()));
        invite.setCreatedAt(createdAt);
        invite.setExpiresAt(createdAt.plus(validDays, ChronoUnit.DAYS));

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String code = generator.newAdminCode();
            invite.setCodeHash(generator.hash(code));
            try {
                if (invites.insertAdminCode(invite) != 1 || invite.getId() == null) {
                    throw new IllegalStateException("Invite insert did not return a generated identifier");
                }
                return new UserInviteViews.AdminCode(invite.getId().toString(), code, invite.getExpiresAt());
            } catch (DuplicateKeyException collision) {
                // 管理员码没有归属人，唯一键冲突时重新生成随机码。
            }
        }
        throw new BusinessException(UserErrorCode.INVITE_CODE_UNAVAILABLE);
    }

    public UserInviteViews.ReferralCode getReferralCode(long userId) {
        return new UserInviteViews.ReferralCode(invites.findReferralCode(userId).orElse(null));
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
