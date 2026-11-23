package com.foodmap.admin.service;

import com.foodmap.admin.dto.AdminUserQuery;
import com.foodmap.admin.vo.AdminUserViews;
import com.foodmap.restaurant.service.RestaurantRequestReadService;
import com.foodmap.review.service.ReviewReadService;
import com.foodmap.user.dto.UserDirectoryEntry;
import com.foodmap.user.service.UserDirectoryService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
    private static final Comparator<RankedUser> DIRECTORY_ORDER =
            Comparator.comparingLong(RankedUser::reviewCount)
                    .thenComparingLong(RankedUser::approvedSubmissionCount)
                    .thenComparingLong(row -> row.user().getId())
                    .reversed();

    private final UserDirectoryService users;
    private final ReviewReadService reviews;
    private final RestaurantRequestReadService submissions;

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
        var records = ranked.subList((int) offset, (int) Math.min(offset + size, total)).stream()
                .map(RankedUser::view)
                .toList();
        return new AdminUserViews.Page(records, total, query.getPage(), size, pages);
    }

    private record RankedUser(UserDirectoryEntry user, long reviewCount, long approvedSubmissionCount) {
        AdminUserViews.Item view() {
            return new AdminUserViews.Item(Long.toString(user.getId()), user.getUsername(),
                    user.isHasAvatar(), reviewCount, approvedSubmissionCount);
        }
    }
}
