package com.foodmap.admin.service;

import com.foodmap.admin.vo.AdminUserViews;
import com.foodmap.common.exception.BusinessException;
import com.foodmap.common.exception.CommonErrorCode;
import com.foodmap.notification.dto.AnnouncementPublishRequest;
import com.foodmap.notification.service.AnnouncementService;
import com.foodmap.user.service.UserMuteService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminUserMuteService {
    private final UserMuteService users;
    private final AnnouncementService announcements;

    /** 状态、指定公告和收件记录一并提交，重复设置同一状态只返回当前结果。 */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AdminUserViews.Mute setMuted(String target, boolean muted) {
        long userId = parseId(target);
        var change = users.setMuted(userId, muted);
        if (change.changed()) {
            var notice = new AnnouncementPublishRequest();
            notice.setClientRequestId(UUID.randomUUID());
            notice.setRecipientUserIds(List.of(target));
            if (muted) {
                notice.setTitle("你的账号已被禁言");
                notice.setContent("由于你发布的内容违反了社区规则，你的账号已被禁言。"
                        + "禁言期间，你仍可浏览店铺和点评，暂时无法发布点评或回复。");
            } else {
                notice.setTitle("你的账号已解除禁言");
                notice.setContent("你此前因违反社区规则受到的禁言已解除，现在可以继续发布点评和回复。"
                        + "欢迎继续分享用餐体验，也请在交流中尊重其他用户、遵守社区规则。");
            }
            announcements.publish(notice, true);
        }
        return new AdminUserViews.Mute(target, change.mutedAt());
    }

    private long parseId(String value) {
        try {
            long id = Long.parseLong(value);
            if (id > 0 && Long.toString(id).equals(value)) {
                return id;
            }
        } catch (NumberFormatException invalid) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
    }
}
