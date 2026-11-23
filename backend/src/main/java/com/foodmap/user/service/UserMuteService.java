package com.foodmap.user.service;

import com.foodmap.common.exception.BusinessException;
import com.foodmap.user.exception.UserErrorCode;
import com.foodmap.user.mapper.UserMuteMapper;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserMuteService {
    private final UserMuteMapper users;

    public record Change(Instant mutedAt, boolean changed) {}

    /** 账号行锁串行化状态变更，同一事务内由管理端服务写入通知。 */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(propagation = Propagation.MANDATORY)
    public Change setMuted(long userId, boolean muted) {
        var user = users.lockTarget(userId);
        if (user == null) {
            throw new BusinessException(UserErrorCode.MUTE_TARGET_UNAVAILABLE);
        }
        Instant previous = user.getMutedAt();
        if (muted == (previous != null)) {
            return new Change(previous, false);
        }
        if (users.setMuted(userId, muted) != 1) {
            throw new IllegalStateException("Missing mute status update");
        }
        return new Change(muted ? users.mutedAt(userId) : null, true);
    }

    /** 在内容写入事务内共享锁定账号，禁言与新点评、回复提交按账号串行生效。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireUnmuted(long userId) {
        if (users.lockUnmuted(userId) == null) {
            throw new BusinessException(UserErrorCode.MUTED);
        }
    }
}
