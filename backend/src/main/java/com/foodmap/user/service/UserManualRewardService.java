package com.foodmap.user.service;

import com.foodmap.user.domain.ManualRewardType;
import com.foodmap.user.dto.ManualRewardEntry;
import com.foodmap.user.mapper.UserManualRewardMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class UserManualRewardService {
    private final UserManualRewardMapper rewards;

    /** 随指定公告和原收件名单一同提交。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void register(List<Long> recipients, ManualRewardType type, String note, long announcement, long admin) {
        rewards.register(recipients, type, note, announcement, admin);
    }

    public long count(long user) {
        return rewards.count(user);
    }

    public List<ManualRewardEntry.SummaryCandidate> summaryCandidates(List<Long> users) {
        return users.isEmpty() ? List.of() : rewards.summaryCandidates(users);
    }

    public List<ManualRewardEntry> page(long user, int limit, long offset) {
        return rewards.page(user, limit, offset);
    }
}
