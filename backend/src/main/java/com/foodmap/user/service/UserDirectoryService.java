package com.foodmap.user.service;

import com.foodmap.user.dto.UserDirectoryEntry;
import com.foodmap.user.mapper.UserDirectoryMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 管理端目录只向聚合服务提供必要的账号信息。 */
@Service
@RequiredArgsConstructor
public class UserDirectoryService {
    private final UserDirectoryMapper users;

    public Page page(String keyword, int page, int size) {
        String term = keyword.strip();
        String pattern = term.isEmpty()
                ? null
                : "%" + term.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        long total = users.count(pattern);
        long offset = ((long) page - 1) * size;
        var records = offset < total ? users.page(pattern, size, offset) : List.<UserDirectoryEntry>of();
        return new Page(total, records);
    }

    public record Page(long total, List<UserDirectoryEntry> records) {}
}
