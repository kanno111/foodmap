package com.foodmap.admin.service;

import com.foodmap.admin.dto.AdminUserQuery;
import com.foodmap.admin.vo.AdminUserViews;
import com.foodmap.notification.service.AnnouncementService;
import com.foodmap.restaurant.service.RestaurantRequestReadService;
import com.foodmap.review.service.ReviewReadService;
import com.foodmap.user.dto.ManualRewardEntry;
import com.foodmap.user.dto.UserDirectoryEntry;
import com.foodmap.user.service.UserDirectoryService;
import com.foodmap.user.service.UserManualRewardService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserDirectoryService {
    private static final int COUNT_BATCH_SIZE = 500;
    private static final int REWARD_SUMMARY_LIMIT = 5;
    private static final AdminUserViews.RewardSummary EMPTY_REWARD_SUMMARY =
            new AdminUserViews.RewardSummary(List.of(), false);
    private static final Comparator<RankedUser> DIRECTORY_ORDER =
            Comparator.comparingLong(RankedUser::reviewCount)
                    .thenComparingLong(RankedUser::approvedSubmissionCount)
                    .thenComparingLong(row -> row.user().getId())
                    .reversed();

    private final UserDirectoryService users;
    private final ReviewReadService reviews;
    private final RestaurantRequestReadService submissions;
    private final UserManualRewardService rewards;
    private final AnnouncementService announcements;

    /** 先按用户名筛选，再用同一快照中的两项计数对全部匹配用户排序。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AdminUserViews.Page page(AdminUserQuery query) {
        int size = query.getPageSize();
        var matches = users.list(query.getKeyword());
        long total = matches.size();
        long offset = ((long) query.getPage() - 1) * size;
        long pages = (total + size - 1) / size;
        if (offset >= total) {
            return new AdminUserViews.Page(List.of(), total, query.getPage(), size, pages);
        }

        var ranked = new ArrayList<RankedUser>(matches.size());
        for (int start = 0; start < matches.size(); start += COUNT_BATCH_SIZE) {
            var batch = matches.subList(start, Math.min(start + COUNT_BATCH_SIZE, matches.size()));
            var ids = batch.stream().map(UserDirectoryEntry::getId).toList();
            var reviewCounts = reviews.countByAuthors(ids);
            var approvedCounts = submissions.countApprovedBySubmitters(ids);
            for (var user : batch) {
                long id = user.getId();
                ranked.add(new RankedUser(
                        user, reviewCounts.getOrDefault(id, 0L), approvedCounts.getOrDefault(id, 0L)));
            }
        }
        ranked.sort(DIRECTORY_ORDER);
        var visible = ranked.subList((int) offset, (int) Math.min(offset + size, total));
        var visibleIds = visible.stream().map(row -> row.user().getId()).toList();
        var inviteCounts = users.inviteCounts(visibleIds);
        var rewardSummaries = rewardSummaries(visibleIds);
        var records = visible.stream()
                .map(row -> row.view(inviteCounts.getOrDefault(row.user().getId(), 0L),
                        rewardSummaries.getOrDefault(row.user().getId(), EMPTY_REWARD_SUMMARY)))
                .toList();
        return new AdminUserViews.Page(records, total, query.getPage(), size, pages);
    }

    private Map<Long, AdminUserViews.RewardSummary> rewardSummaries(List<Long> ids) {
        var rows = rewards.summaryCandidates(ids);
        var sources = announcements.rewardSources(rows.stream()
                .map(ManualRewardEntry.SummaryCandidate::sourceAnnouncementId).distinct().toList());
        // 历史补录的登记可能晚于新发奖，优先使用原通知时间复原顺序。
        var order = Comparator.comparing((ManualRewardEntry.SummaryCandidate row) -> {
            var source = sources.get(row.sourceAnnouncementId());
            return source == null ? row.recordedAt() : source.publishedAt();
        }).thenComparingLong(ManualRewardEntry.SummaryCandidate::id);
        return rows.stream().collect(Collectors.groupingBy(ManualRewardEntry.SummaryCandidate::userId,
                Collectors.collectingAndThen(Collectors.toList(), group -> {
                    var types = group.stream().sorted(order).limit(REWARD_SUMMARY_LIMIT)
                            .map(ManualRewardEntry.SummaryCandidate::rewardType).toList();
                    return new AdminUserViews.RewardSummary(types, group.size() > REWARD_SUMMARY_LIMIT);
                })));
    }

    private record RankedUser(UserDirectoryEntry user, long reviewCount, long approvedSubmissionCount) {
        AdminUserViews.Item view(long invitedRegistrationCount, AdminUserViews.RewardSummary rewards) {
            return new AdminUserViews.Item(Long.toString(user.getId()), user.getUsername(),
                    user.isHasAvatar(), reviewCount, approvedSubmissionCount, invitedRegistrationCount,
                    rewards, user.getMutedAt(), user.isCanModerate());
        }
    }
}
