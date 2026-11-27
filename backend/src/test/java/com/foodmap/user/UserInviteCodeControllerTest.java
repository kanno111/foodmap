package com.foodmap.user;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.foodmap.security.authentication.SessionJwtAuthenticationConverter;
import com.foodmap.security.config.SecurityConfiguration;
import com.foodmap.security.service.CurrentActorService;
import com.foodmap.user.controller.UserInviteCodeController;
import com.foodmap.user.domain.AdminInviteStatus;
import com.foodmap.user.service.UserInviteCodeService;
import com.foodmap.user.vo.UserInviteViews;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.List;

@WebMvcTest(UserInviteCodeController.class)
@Import({SecurityConfiguration.class, CurrentActorService.class, UserInviteCodeControllerTest.WebSecurity.class})
class UserInviteCodeControllerTest {
    private static final String ADMIN_ENDPOINT = "/api/admin/invite-codes";
    private static final String REFERRAL_ENDPOINT = "/api/me/invite-code";

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private UserInviteCodeService invites;
    @MockitoBean
    private SessionJwtAuthenticationConverter converter;
    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void requiresTheApplicableRoleForEachRoute() throws Exception {
        mvc.perform(post(ADMIN_ENDPOINT)).andExpect(status().isUnauthorized());
        mvc.perform(get(REFERRAL_ENDPOINT)).andExpect(status().isUnauthorized());
        mvc.perform(put(REFERRAL_ENDPOINT)).andExpect(status().isUnauthorized());
        mvc.perform(post(ADMIN_ENDPOINT).with(actor("22", "ROLE_USER")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alumnus@example.test\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(put(REFERRAL_ENDPOINT).with(actor("11", "ROLE_ADMIN")))
                .andExpect(status().isForbidden());
        mvc.perform(get(REFERRAL_ENDPOINT).with(actor("11", "ROLE_ADMIN")))
                .andExpect(status().isForbidden());
        mvc.perform(get(ADMIN_ENDPOINT).with(actor("22", "ROLE_USER"))).andExpect(status().isForbidden());
        mvc.perform(put(ADMIN_ENDPOINT + "/101/revocation").with(actor("22", "ROLE_USER")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(invites);
    }

    @Test
    void readsOnlyTheCurrentOwnersCodeWithoutGeneratingIt() throws Exception {
        when(invites.getReferralCode(22L)).thenReturn(new UserInviteViews.ReferralCode(null));
        mvc.perform(get(REFERRAL_ENDPOINT).with(actor("22", "ROLE_USER")).param("userId", "33"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.code").value(nullValue()));
        verify(invites).getReferralCode(22L);
        verifyNoMoreInteractions(invites);
    }

    @Test
    void adminCanCreateCodeAndReceivesANonCacheableResponse() throws Exception {
        when(invites.createAdminCode(eq(11L), any())).thenReturn(
                new UserInviteViews.AdminCode("101", "ADMINCODE", Instant.parse("2026-12-04T08:00:00Z")));
        mvc.perform(post(ADMIN_ENDPOINT).with(actor("11", "ROLE_ADMIN")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"validDays\":7}"))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.id").value("101")).andExpect(jsonPath("$.data.code").value("ADMINCODE"));
    }

    @Test
    void administratorCanReadStatusAndRevokeAnInvite() throws Exception {
        var record = new UserInviteViews.AdminRecord("101", null, "alumnus", AdminInviteStatus.USED,
                Instant.EPOCH, Instant.EPOCH.plusSeconds(7));
        when(invites.listAdminCodes(AdminInviteStatus.USED, 1))
                .thenReturn(new UserInviteViews.AdminPage(List.of(record), false));
        mvc.perform(get(ADMIN_ENDPOINT).with(actor("11", "ROLE_ADMIN")).param("status", "USED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].registeredUsername").value("alumnus"))
                .andExpect(jsonPath("$.data.records[0].code").doesNotExist());
        mvc.perform(put(ADMIN_ENDPOINT + "/101/revocation").with(actor("11", "ROLE_ADMIN")))
                .andExpect(status().isOk());
        verify(invites).revokeAdminCode(101L);
    }

    @Test
    void validatesEmailAndExpiryBeforeIssuing() throws Exception {
        mvc.perform(post(ADMIN_ENDPOINT).with(actor("11", "ROLE_ADMIN")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"invalid\",\"validDays\":31}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data[0].field").value("email"))
                .andExpect(jsonPath("$.data[1].field").value("validDays"));
        verifyNoInteractions(invites);
    }

    @Test
    void referralCodeIsAlwaysForTheAuthenticatedOwner() throws Exception {
        when(invites.getOrCreateReferralCode(22L)).thenReturn(new UserInviteViews.ReferralCode("REFERRALCODE"));
        mvc.perform(put(REFERRAL_ENDPOINT).with(actor("22", "ROLE_USER")).param("userId", "33")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":33}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.code").value("REFERRALCODE"));
        verify(invites).getOrCreateReferralCode(22L);
    }

    private static RequestPostProcessor actor(String id, String role) {
        return jwt().jwt(token -> token.subject(id)).authorities(new SimpleGrantedAuthority(role));
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class WebSecurity {
    }
}
