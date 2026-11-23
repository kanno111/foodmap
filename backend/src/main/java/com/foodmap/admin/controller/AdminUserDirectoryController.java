package com.foodmap.admin.controller;

import com.foodmap.admin.dto.AdminUserQuery;
import com.foodmap.admin.service.AdminUserDirectoryService;
import com.foodmap.admin.vo.AdminUserViews;
import com.foodmap.common.api.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/users")
public class AdminUserDirectoryController {
    private final AdminUserDirectoryService directory;

    @GetMapping
    public ResponseEntity<ApiResponse<AdminUserViews.Page>> page(@Valid @ModelAttribute AdminUserQuery query) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(directory.page(query)));
    }
}
