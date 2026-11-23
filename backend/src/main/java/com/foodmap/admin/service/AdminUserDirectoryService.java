package com.foodmap.admin.service;

import com.foodmap.admin.dto.AdminUserQuery;
import com.foodmap.admin.vo.AdminUserViews;
import com.foodmap.restaurant.service.RestaurantRequestReadService;
import com.foodmap.review.service.ReviewReadService;
import com.foodmap.user.dto.UserDirectoryEntry;
import com.foodmap.user.service.UserDirectoryService;
import java.util.ArrayList;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserDirectoryService {
    private final UserDirectoryService users;
    private final ReviewReadService reviews;
    private final RestaurantRequestReadService submissions;

    /** 稳定分页后批量统计本页用户，列表与两项计数共享一个数据库快照。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AdminUserViews.Page page(AdminUserQuery query) {
        int size = query.getPageSize();
        var result = users.page(query.getKeyword(), query.getPage(), size);
        var ids = result.records().stream().map(UserDirectoryEntry::getId).toList();
        var reviewCounts = reviews.countByAuthors(ids);
        var approvedCounts = submissions.countApprovedBySubmitters(ids);
        var records = new ArrayList<AdminUserViews.Item>(ids.size());
        for (var row : result.records()) {
            long id = row.getId();
            records.add(new AdminUserViews.Item(
                    Long.toString(id), row.getUsername(), row.isHasAvatar(),
                    reviewCounts.getOrDefault(id, 0L), approvedCounts.getOrDefault(id, 0L)));
        }
        long pages = (result.total() + size - 1) / size;
        return new AdminUserViews.Page(records, result.total(), query.getPage(), size, pages);
    }
}
