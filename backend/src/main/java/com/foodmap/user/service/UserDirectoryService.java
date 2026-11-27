package com.foodmap.user.service;

import com.foodmap.user.dto.UserDirectoryEntry;
import com.foodmap.user.mapper.UserDirectoryMapper;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 管理端目录只向聚合服务提供必要的账号信息。 */
@Service
@RequiredArgsConstructor
public class UserDirectoryService {
    private final UserDirectoryMapper users;

    public List<UserDirectoryEntry> list(String keyword) {
        return users.list(pattern(keyword));
    }

    public List<UserDirectoryEntry> searchActive(String keyword, long offset, int limit) {
        return users.searchActive(pattern(keyword), offset, limit);
    }

    /** 管理历史根据已保存的账号 ID 批量读取当前用户名。 */
    public Map<Long, String> namesByIds(List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return users.namesByIds(ids).stream().collect(
                Collectors.toMap(UserDirectoryEntry::getId, UserDirectoryEntry::getUsername));
    }

    /** 在发布事务中锁定可登录的收件账号，避免校验和保存之间变更状态。 */
    public List<Long> lockActiveIds(List<Long> ids) {
        return users.lockActiveIds(ids);
    }

    private String pattern(String keyword) {
        String term = keyword.strip();
        return term.isEmpty()
                ? null
                : "%" + term.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }
}
