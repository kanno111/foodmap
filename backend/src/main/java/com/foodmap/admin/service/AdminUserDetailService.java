package com.foodmap.admin.service;

import com.foodmap.admin.dto.AdminUserQuery;
import com.foodmap.admin.vo.AdminUserViews;
import com.foodmap.common.exception.BusinessException;
import com.foodmap.common.exception.CommonErrorCode;
import com.foodmap.notification.service.AnnouncementService;
import com.foodmap.restaurant.service.RestaurantRequestReadService;
import com.foodmap.review.service.ReviewReadService;
import com.foodmap.user.dto.UserDirectoryEntry;
import com.foodmap.user.dto.ManualRewardEntry;
import com.foodmap.user.service.UserDirectoryService;
import com.foodmap.user.service.UserManualRewardService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class AdminUserDetailService {
    private final UserDirectoryService users;
    private final ReviewReadService reviews;
    private final RestaurantRequestReadService submissions;
    private final UserManualRewardService rewards;
    private final AnnouncementService announcements;

    public AdminUserViews.Profile profile(String id) {
        var user = requireUser(id);
        long userId = user.getId();
        var ids = List.of(userId);
        var source = users.registrationSource(userId);
        return new AdminUserViews.Profile(id, user.getUsername(), user.isHasAvatar(),
                reviews.countByAuthors(ids).getOrDefault(userId, 0L),
                submissions.countApprovedBySubmitters(ids).getOrDefault(userId, 0L),
                users.inviteCounts(ids).getOrDefault(userId, 0L), user.getMutedAt(),
                user.isCanModerate(), user.getCreatedAt(), user.getStatus(),
                new AdminUserViews.RegistrationSource(source.kind(), source.inviterName()));
    }

    public AdminUserViews.DetailPage<AdminUserViews.Review> reviews(String id, AdminUserQuery query) {
        long user = requireUser(id).getId();
        long total = reviews.countByAuthors(List.of(user)).getOrDefault(user, 0L);
        long offset = offset(query);
        var records = offset >= total ? List.<AdminUserViews.Review>of()
                : reviews.pageByAuthor(user, query.getPageSize(), offset).stream().map(entry -> {
                    var review = entry.review();
                    return new AdminUserViews.Review(review.getId(), entry.restaurantName(), review.getBody(),
                            review.getOverallScore(), review.getRecommendation(), review.getScoringStatus(),
                            review.getCreatedAt(), review.getScores(), review.getPhotos());
                }).toList();
        return page(records, total, query);
    }

    public AdminUserViews.DetailPage<AdminUserViews.Invitee> invitees(String id, AdminUserQuery query) {
        long user = requireUser(id).getId();
        long total = users.inviteCounts(List.of(user)).getOrDefault(user, 0L);
        long offset = offset(query);
        var records = offset >= total ? List.<AdminUserViews.Invitee>of()
                : users.invitees(user, query.getPageSize(), offset).stream()
                        .map(row -> new AdminUserViews.Invitee(Long.toString(row.id()), row.username(),
                                row.hasAvatar(), row.registeredAt(), row.profileAvailable()))
                        .toList();
        return page(records, total, query);
    }

    public AdminUserViews.DetailPage<AdminUserViews.Reward> rewards(String id, AdminUserQuery query) {
        long user = requireUser(id).getId();
        long total = rewards.count(user);
        long offset = offset(query);
        if (offset >= total) {
            return page(List.of(), total, query);
        }
        var rows = rewards.page(user, query.getPageSize(), offset);
        var sources = announcements.rewardSources(rows.stream().map(ManualRewardEntry::sourceAnnouncementId)
                .distinct().toList());
        var records = rows.stream().map(row -> {
            var source = sources.get(row.sourceAnnouncementId());
            return new AdminUserViews.Reward(Long.toString(row.id()), row.rewardType(), row.rewardNote(),
                    Long.toString(row.sourceAnnouncementId()), source == null ? null : source.title(),
                    source != null && source.withdrawn(), row.recordedAt());
        }).toList();
        return page(records, total, query);
    }

    private UserDirectoryEntry requireUser(String id) {
        long key;
        try {
            key = Long.parseLong(id);
            if (key < 1 || !Long.toString(key).equals(id)) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException invalid) {
            throw new BusinessException(CommonErrorCode.VALIDATION_FAILED);
        }
        var user = users.find(key);
        if (user == null) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND, "用户不存在。");
        }
        return user;
    }

    private long offset(AdminUserQuery query) {
        return (long) (query.getPage() - 1) * query.getPageSize();
    }

    private <T> AdminUserViews.DetailPage<T> page(List<T> records, long total, AdminUserQuery query) {
        return new AdminUserViews.DetailPage<>(records, Math.ceilDiv(total, query.getPageSize()));
    }
}
