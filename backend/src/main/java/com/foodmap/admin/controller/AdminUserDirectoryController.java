package com.foodmap.admin.controller;

import com.foodmap.admin.dto.AdminUserMuteRequest;
import com.foodmap.admin.dto.AdminUserQuery;
import com.foodmap.admin.service.AdminUserDirectoryService;
import com.foodmap.admin.service.AdminUserMuteService;
import com.foodmap.admin.vo.AdminUserViews;
import com.foodmap.common.api.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/users")
public class AdminUserDirectoryController {
    private final AdminUserDirectoryService directory;
    private final AdminUserMuteService moderation;

    @GetMapping
    public ResponseEntity<ApiResponse<AdminUserViews.Page>> page(@Valid @ModelAttribute AdminUserQuery query) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(directory.page(query)));
    }

    @PutMapping("/{id}/mute")
    public ResponseEntity<ApiResponse<AdminUserViews.Mute>> setMuted(
            @PathVariable String id, @Valid @RequestBody AdminUserMuteRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(moderation.setMuted(id, request.getMuted())));
    }
}
