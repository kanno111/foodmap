package com.foodmap.user.controller;

import com.foodmap.common.api.ApiResponse;
import com.foodmap.security.service.CurrentActorService;
import com.foodmap.user.dto.AdminInviteCreateRequest;
import com.foodmap.user.service.UserInviteCodeService;
import com.foodmap.user.vo.UserInviteViews;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class UserInviteCodeController {
    private final CurrentActorService actors;
    private final UserInviteCodeService invites;

    @PostMapping("/api/admin/invite-codes")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<UserInviteViews.AdminCode>> createAdminCode(
            @Valid @RequestBody AdminInviteCreateRequest request) {
        var code = invites.createAdminCode(actors.requireActor().getUserId(), request);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(ApiResponse.ok(code));
    }

    @GetMapping("/api/me/invite-code")
    @PreAuthorize("hasRole('USER') and !hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<UserInviteViews.ReferralCode>> getReferralCode() {
        var code = invites.getReferralCode(actors.requireActor().getUserId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(code));
    }

    @PutMapping("/api/me/invite-code")
    @PreAuthorize("hasRole('USER') and !hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<UserInviteViews.ReferralCode>> getOrCreateReferralCode() {
        var code = invites.getOrCreateReferralCode(actors.requireActor().getUserId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(code));
    }
}
