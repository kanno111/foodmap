package com.foodmap.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.foodmap.notification.mapper.NotificationMapper;
import com.foodmap.notification.service.NotificationPublicationService;
import com.foodmap.security.TestJwtKeys;
import com.foodmap.security.config.AuthenticationProperties;
import com.foodmap.security.service.LoginSessionService;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 隔离MySQL验证全体公告生命周期、混合收件箱、快照与并发；不写开发业务库。 */
@ActiveProfiles("test")
@SpringBootTest(
        properties = {
            "spring.flyway.enabled=true",
            "spring.sql.init.mode=never",
            "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
            "spring.datasource.hikari.minimum-idle=0"
        })
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(TestJwtKeys.class)
@EnabledIfSystemProperty(named = "review.integration", matches = "true")
class AnnouncementApiIntegrationTest {
    private static String database;
    private static boolean created;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtEncoder encoder;
    @Autowired AuthenticationProperties authentication;
    @Autowired PlatformTransactionManager transactions;
    @Autowired NotificationPublicationService publications;
    @Autowired SqlSessionTemplate sql;
    @MockitoBean LoginSessionService sessions;
    @MockitoSpyBean NotificationMapper messages;
    @MockitoSpyBean com.foodmap.notification.service.NotificationStreamService streams;
    @MockitoSpyBean com.foodmap.notification.mapper.AnnouncementMapper announcements;
    private String owner;
    private String other;

    /** 开启集成测试仍必须显式提供回环地址，拒绝连接开发业务库。 */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws SQLException {
        database = "foodmap_announcement_api_it_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = connect("");
                var statement = connection.createStatement()) {
            statement.executeUpdate(
                    "CREATE DATABASE `"
                            + database
                            + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            created = true;
        }
        registry.add("spring.datasource.url", () -> url(database));
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", AnnouncementApiIntegrationTest::password);
    }

    @BeforeEach
    void setup() {
        reset(sessions, messages, announcements, streams);
        jdbc.update("DELETE FROM user_manual_reward_records");
        jdbc.update("DELETE FROM notification_announcement_user_states");
        jdbc.update("DELETE FROM notification_announcement_recipients");
        jdbc.update("DELETE FROM notification_announcements");
        jdbc.update("DELETE FROM notification_messages");
        jdbc.update("DELETE FROM user_roles");
        jdbc.update("DELETE FROM users");
        for (long user : List.of(11L, 22L)) {
            jdbc.update(
                    "INSERT INTO users(id,username,email,password_hash,email_verified)"
                            + " VALUES(?,?,?,?,TRUE)",
                    user,
                    "notice_" + user,
                    "notice" + user + "@example.test",
                    "test-only");
            jdbc.update(
                    "INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE code=?",
                    user,
                    user == 11 ? "ROLE_USER" : "ROLE_ADMIN");
        }
        when(sessions.isActive(
                        org.mockito.ArgumentMatchers.anyString(),
                        anyLong(),
                        org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(true);
        owner = token(11);
        other = token(22);
    }

    /** 只清理本次成功创建且名称匹配的临时库，不使用全局clean。 */
    @AfterAll
    static void cleanup() throws SQLException {
        if (!created) {
            return;
        }
        drop(database);
    }

    /** 普通用户不能管理公告；角色变化即使沿用旧JWT也必须重新核验。 */
    @Test
    void managementUsesCurrentAdministratorRole() throws Exception {
        mvc.perform(get("/api/admin/announcements")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/announcements").header("Authorization", "Bearer " + owner))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/api/admin/announcements")
                                .header("Authorization", "Bearer " + owner)
                                .contentType("application/json")
                                .content(body(UUID.randomUUID(), "标题", "正文")))
                .andExpect(status().isForbidden());
        long id = publish();
        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                                        "/api/admin/announcements/" + id)
                                .header("Authorization", "Bearer " + other)
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(status().isMethodNotAllowed());
        jdbc.update("DELETE FROM user_roles WHERE user_id=22");
        mvc.perform(
                        post("/api/admin/announcements/" + id + "/withdraw")
                                .header("Authorization", "Bearer " + other))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/me/announcements/" + id)).andExpect(status().isUnauthorized());
    }

    @Test
    void normalizedPublishReplayConflictAndValidation() throws Exception {
        var uuid = UUID.randomUUID();
        var first =
                publish(other, uuid, " 标题 ", " 正文 ")
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.title").value("标题"))
                        .andExpect(jsonPath("$.data.replayed").value(false))
                        .andExpect(jsonPath("$.data.requestHash").doesNotExist());
        String id = id(first);
        publish(other, uuid, "标题", "正文")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.replayed").value(true));
        publish(other, uuid, "标题", "不同正文").andExpect(status().isConflict());
        publish(other, UUID.randomUUID(), " ", "正文").andExpect(status().isBadRequest());
        publish(other, UUID.randomUUID(), "中".repeat(121), "正文").andExpect(status().isBadRequest());
        publish(other, UUID.randomUUID(), "标题", "中".repeat(10001))
                .andExpect(status().isBadRequest());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM notification_announcements", Long.class))
                .isEqualTo(1);
        org.mockito.Mockito.verify(streams).changed(0);
    }

    @Test
    void targetedAnnouncementAppearsOnlyForSelectedAccounts() throws Exception {
        jdbc.update(
                "INSERT INTO users(id,username,email,password_hash,email_verified)"
                        + " VALUES(33,'later','later@example.test','fixture',TRUE)");
        String excluded = token(33);
        var request = UUID.randomUUID();
        var sent = publishTargeted(other, request, List.of("11", "22"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.audience").value("TARGETED"));
        long id = Long.parseLong(id(sent));
        // 旧版本的收件箱只读取 PUBLISHED，回滚后也不会把指定公告当作全体公告。
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification_announcements WHERE id=? AND status='PUBLISHED'",
                Long.class, id)).isZero();
        publishTargeted(other, request, List.of("22", "11", "11"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replayed").value(true))
                .andExpect(jsonPath("$.data.id").value(Long.toString(id)));
        publishTargeted(other, request, List.of("11")).andExpect(status().isConflict());
        publish(other, request, "标题", "正文").andExpect(status().isConflict());
        org.mockito.Mockito.verify(streams).changed(11);
        org.mockito.Mockito.verify(streams).changed(22);
        org.mockito.Mockito.verify(streams, org.mockito.Mockito.never()).changed(0);

        inbox(owner, "SYSTEM", 1, 20)
                .andExpect(jsonPath("$.data.records[0].announcementId").value(Long.toString(id)));
        unread(owner, 1);
        unread(other, 1);
        inbox(excluded, "ALL", 1, 20)
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.latestSystem").isEmpty());
        unread(excluded, 0);
        mvc.perform(get("/api/me/announcements/" + id).header("Authorization", "Bearer " + excluded))
                .andExpect(status().isNotFound());
        read(excluded, id).andExpect(status().isNotFound());
        remove(excluded, id).andExpect(status().isNotFound());
        mvc.perform(post("/api/me/notifications/read-all")
                        .header("Authorization", "Bearer " + excluded))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification_announcement_user_states", Long.class)).isZero();

        mvc.perform(get("/api/me/announcements/" + id).header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content").value("正文"));
        mvc.perform(post("/api/me/notifications/read-all")
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isNoContent());
        unread(owner, 0);
        remove(owner, id).andExpect(status().isNoContent());
        unread(other, 1);

        reset(streams);
        withdraw(id).andExpect(status().isOk());
        unread(other, 0);
        org.mockito.Mockito.verify(streams).changed(11);
        org.mockito.Mockito.verify(streams).changed(22);
        org.mockito.Mockito.verifyNoMoreInteractions(streams);
    }

    @Test
    void onlyAdministratorDetailIncludesHistoricalRecipients() throws Exception {
        var requestId = id(publishTargeted(other, UUID.randomUUID(), List.of("22", "11"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.recipients").doesNotExist()));
        var adminPath = "/api/admin/announcements/" + requestId;
        mvc.perform(get(adminPath).header("Authorization", "Bearer " + owner))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/me/announcements/" + requestId).header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recipients").doesNotExist());
        mvc.perform(get("/api/admin/announcements").header("Authorization", "Bearer " + other))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].recipients").doesNotExist());
        jdbc.update("UPDATE users SET username='renamed_notice' WHERE id=11");
        withdraw(Long.parseLong(requestId)).andExpect(status().isOk());
        mvc.perform(get(adminPath).header("Authorization", "Bearer " + other))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WITHDRAWN"))
                .andExpect(jsonPath("$.data.recipients.length()").value(2))
                .andExpect(jsonPath("$.data.recipients[0].id").value("11"))
                .andExpect(jsonPath("$.data.recipients[0].username").value("renamed_notice"))
                .andExpect(jsonPath("$.data.recipients[1].username").value("notice_22"));
        jdbc.update("UPDATE users SET status='DELETED' WHERE id=11");
        mvc.perform(get(adminPath).header("Authorization", "Bearer " + other))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recipients[0].id").value("11"))
                .andExpect(jsonPath("$.data.recipients[0].username").value(nullValue()));
        var allId = id(publish(other, UUID.randomUUID(), "标题", "正文").andExpect(status().isOk()));
        mvc.perform(get("/api/admin/announcements/" + allId).header("Authorization", "Bearer " + other))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recipients.length()").value(0));
    }

    @Test
    void recipientSearchAndPublishRequireAdministratorAndEligibleRecipients() throws Exception {
        jdbc.update("UPDATE users SET email_verified=FALSE WHERE id=11");
        mvc.perform(get("/api/admin/announcements/recipients")
                        .header("Authorization", "Bearer " + other)
                        .param("keyword", "notice_"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value("22"));
        mvc.perform(get("/api/admin/announcements/recipients")
                        .header("Authorization", "Bearer " + other)
                        .param("page", "0"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/announcements/recipients"))
                .andExpect(status().isUnauthorized());
        jdbc.update("UPDATE users SET email_verified=TRUE WHERE id=11");
        mvc.perform(get("/api/admin/announcements/recipients")
                        .header("Authorization", "Bearer " + owner))
                .andExpect(status().isForbidden());
        publishTargeted(owner, UUID.randomUUID(), List.of("11")).andExpect(status().isForbidden());
        publishTargeted(other, UUID.randomUUID(), List.of()).andExpect(status().isBadRequest());
        publishTargeted(other, UUID.randomUUID(), List.of("11", "999")).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_announcements", Long.class))
                .isZero();
        org.mockito.Mockito.verifyNoInteractions(streams);
    }

    @Test
    void accountMuteAndNoticeCommitTogetherAndRepeatedOperationsSendOnlyOnce() throws Exception {
        setMuted(owner, 11, true).andExpect(status().isForbidden());
        setMuted(other, 22, true).andExpect(status().isNotFound());
        org.mockito.Mockito.doThrow(new IllegalStateException("fixture delivery failure"))
                .when(announcements)
                .insertRecipients(anyLong(), org.mockito.ArgumentMatchers.anyList());
        setMuted(other, 11, true).andExpect(status().isInternalServerError());
        assertThat(jdbc.queryForObject("SELECT muted_at FROM users WHERE id=11", Instant.class))
                .isNull();
        unread(owner, 0);
        org.mockito.Mockito.verifyNoInteractions(streams);
        reset(announcements);

        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            java.util.concurrent.Callable<String> mute = () -> {
                start.await();
                var body = setMuted(other, 11, true).andExpect(status().isOk()).andReturn()
                        .getResponse().getContentAsString();
                return new tools.jackson.databind.ObjectMapper()
                        .readTree(body).at("/data/mutedAt").asText();
            };
            var first = pool.submit(mute);
            var second = pool.submit(mute);
            start.countDown();
            assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(second.get(15, TimeUnit.SECONDS));
        }
        mvc.perform(get("/api/admin/users").param("keyword", "notice_11")
                        .header("Authorization", "Bearer " + other))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].canModerate").value(true))
                .andExpect(jsonPath("$.data.records[0].mutedAt").isNotEmpty());
        mvc.perform(get("/auth/me").header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk());
        var listing = inbox(owner, "SYSTEM", 1, 20)
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].announcementTitle").value("你的账号已被禁言"));
        String id = new tools.jackson.databind.ObjectMapper()
                .readTree(listing.andReturn().getResponse().getContentAsString())
                .at("/data/records/0/announcementId").asText();
        mvc.perform(get("/api/me/announcements/" + id).header("Authorization", "Bearer " + owner))
                .andExpect(jsonPath("$.data.content").value(org.hamcrest.Matchers.containsString(
                        "由于你发布的内容违反了社区规则")));
        unread(other, 0);

        setMuted(other, 11, false).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mutedAt").isEmpty());
        setMuted(other, 11, false).andExpect(status().isOk());
        unread(other, 0);
        inbox(owner, "SYSTEM", 1, 20)
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.records[0].announcementTitle").value("你的账号已解除禁言"));
        org.mockito.Mockito.verify(streams, org.mockito.Mockito.times(2)).changed(11);
        org.mockito.Mockito.verifyNoMoreInteractions(streams);
    }

    @Test
    void futureUserSeesHistoricalUnreadWithoutDeliveryRowsAndGetDoesNotRead() throws Exception {
        long id = publish();
        jdbc.update(
                "INSERT INTO users(id,username,email,password_hash,email_verified)"
                        + " VALUES(33,'later','later@example.test','fixture',TRUE)");
        String later = token(33);
        inbox(later, "SYSTEM", 1, 20)
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.unreadCount").value(1))
                .andExpect(jsonPath("$.data.latestSystem.id").value("announcement:" + id))
                .andExpect(jsonPath("$.data.records[0].announcementTitle").value("标题"));
        mvc.perform(get("/api/me/announcements/" + id).header("Authorization", "Bearer " + later))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value("正文"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_messages", Long.class))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM notification_announcement_user_states",
                                Long.class))
                .isZero();
        unread(later, 1);
    }

    @Test
    void personalReadDeleteAreIdempotentAndIndependent() throws Exception {
        long id = publish();
        read(owner, id).andExpect(status().isNoContent());
        var first =
                jdbc.queryForMap(
                        "SELECT * FROM notification_announcement_user_states WHERE user_id=11");
        read(owner, id).andExpect(status().isNoContent());
        assertThat(
                        jdbc.queryForMap(
                                "SELECT * FROM notification_announcement_user_states WHERE"
                                        + " user_id=11"))
                .isEqualTo(first);
        remove(owner, id).andExpect(status().isNoContent());
        var deleted =
                jdbc.queryForMap(
                        "SELECT * FROM notification_announcement_user_states WHERE user_id=11");
        remove(owner, id).andExpect(status().isNoContent());
        assertThat(
                        jdbc.queryForMap(
                                "SELECT * FROM notification_announcement_user_states WHERE"
                                        + " user_id=11"))
                .isEqualTo(deleted);
        read(owner, id).andExpect(status().isNotFound());
        mvc.perform(get("/api/me/announcements/" + id).header("Authorization", "Bearer " + owner))
                .andExpect(status().isNotFound());
        unread(owner, 0);
        unread(other, 1);
        remove(other, id).andExpect(status().isNoContent());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT read_at FROM notification_announcement_user_states WHERE"
                                        + " user_id=22",
                                java.sql.Timestamp.class))
                .isNull();
    }

    @Test
    void withdrawalRemovesListDetailSummaryAndUnreadButKeepsFirstReceipt() throws Exception {
        long id = publish();
        remove(owner, id).andExpect(status().isNoContent());
        var first =
                withdraw(id)
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.replayed").value(false));
        String receipt = first.andReturn().getResponse().getContentAsString();
        withdraw(id).andExpect(status().isOk()).andExpect(jsonPath("$.data.replayed").value(true));
        var json = new tools.jackson.databind.ObjectMapper();
        assertThat(
                        json.readTree(withdraw(id).andReturn().getResponse().getContentAsString())
                                .get("data")
                                .get("withdrawnAt")
                                .asText())
                .isEqualTo(json.readTree(receipt).get("data").get("withdrawnAt").asText());
        unread(other, 0);
        inbox(other, "ALL", 1, 20)
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.latestSystem").isEmpty());
        mvc.perform(get("/api/me/announcements/" + id).header("Authorization", "Bearer " + other))
                .andExpect(status().isNotFound());
        remove(owner, id).andExpect(status().isNoContent());
        remove(other, id).andExpect(status().isNotFound());
        publish(other, lastRequest, "标题", "正文")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WITHDRAWN"));
        org.mockito.Mockito.verify(streams, org.mockito.Mockito.times(2)).changed(0);
    }

    @Test
    void combinedPaginationSeparatesCollidingIdsAndCountsAllCategories() throws Exception {
        long id = publish();
        jdbc.update(
                "INSERT INTO"
                    + " notification_messages(id,recipient_user_id,notification_type,source_id,review_id,actor_user_id,created_at)"
                    + " VALUES(?,11,'REVIEW_REPLY',100,1,22,'2026-01-01 00:00:00')",
                id);
        inbox(owner, "ALL", 1, 1)
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.records[0].id").value("announcement:" + id));
        inbox(owner, "ALL", 2, 1)
                .andExpect(jsonPath("$.data.records[0].id").value(Long.toString(id)));
        read(owner, id).andExpect(status().isNoContent());
        unread(owner, 1);
        mvc.perform(
                        post("/api/me/notifications/" + id + "/read")
                                .header("Authorization", "Bearer " + owner))
                .andExpect(status().isNoContent());
        unread(owner, 0);
    }

    @Test
    void categoryReadAllExcludesAnnouncementsUntilSystemOrAll() throws Exception {
        publish();
        publish();
        mvc.perform(
                        post("/api/me/notifications/read-all")
                                .param("category", "COMMENTS")
                                .header("Authorization", "Bearer " + owner))
                .andExpect(status().isNoContent());
        unread(owner, 2);
        mvc.perform(
                        post("/api/me/notifications/read-all")
                                .param("category", "SYSTEM")
                                .header("Authorization", "Bearer " + owner))
                .andExpect(status().isNoContent());
        unread(owner, 0);
        unread(other, 2);
    }

    @Test
    void snapshotDoesNotReadSmallerIdCommittedAfterItStarted() throws Exception {
        long id = publish();
        try (var connection = connect(database)) {
            connection.setAutoCommit(false);
            try (var statement =
                    connection.prepareStatement(
                            "INSERT INTO"
                                + " notification_announcements(id,client_request_id,request_hash,title,content,published_by)"
                                + " VALUES(?,UNHEX(REPEAT('bb',16)),UNHEX(REPEAT('cc',32)),'迟到公告','正文',22)")) {
                statement.setLong(1, id + 1);
                statement.executeUpdate();
            }
            java.util.concurrent.atomic.AtomicBoolean once =
                    new java.util.concurrent.atomic.AtomicBoolean();
            doAnswer(
                            invocation -> {
                                var rows =
                                        sql.getMapper(
                                                        com.foodmap.notification.mapper
                                                                .AnnouncementMapper.class)
                                                .unreadIds(
                                                        invocation.getArgument(0),
                                                        invocation.getArgument(1));
                                if (once.compareAndSet(false, true)) {
                                    connection.commit();
                                }
                                return rows;
                            })
                    .when(announcements)
                    .unreadIds(anyLong(), anyLong());
            mvc.perform(
                            post("/api/me/notifications/read-all")
                                    .param("category", "ALL")
                                    .header("Authorization", "Bearer " + owner))
                    .andExpect(status().isNoContent());
            unread(owner, 1);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM notification_announcement_user_states"
                                            + " WHERE announcement_id=?",
                                    Long.class,
                                    id + 1))
                    .isZero();
        }
    }

    @Test
    void concurrentDuplicatePublicationCreatesExactlyOneAnnouncement() throws Exception {
        var uuid = UUID.randomUUID();
        try (var pool = Executors.newFixedThreadPool(4)) {
            var start = new CountDownLatch(1);
            var jobs = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i = 0; i < 4; i++) {
                jobs.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return publish(other, uuid, "标题", "正文")
                                            .andReturn()
                                            .getResponse()
                                            .getStatus();
                                }));
            }
            start.countDown();
            for (var job : jobs) {
                assertThat(job.get(15, TimeUnit.SECONDS)).isEqualTo(200);
            }
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM notification_announcements", Long.class))
                .isEqualTo(1);
        org.mockito.Mockito.verify(streams).changed(0);
    }

    @Test
    void failedPublishAndReadAllRollbackAndSendNoChange() throws Exception {
        org.mockito.Mockito.doThrow(new IllegalStateException("fixture readback failure"))
                .when(announcements)
                .find(anyLong());
        publish(other, UUID.randomUUID(), "标题", "正文").andExpect(status().isInternalServerError());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM notification_announcements", Long.class))
                .isZero();
        org.mockito.Mockito.verify(streams, org.mockito.Mockito.never()).changed(0);
        reset(announcements);
        long first = publish(), second = publish();
        reset(streams);
        org.mockito.Mockito.doThrow(new IllegalStateException("fixture state failure"))
                .when(announcements)
                .read(11, second);
        mvc.perform(
                        post("/api/me/notifications/read-all")
                                .param("category", "ALL")
                                .header("Authorization", "Bearer " + owner))
                .andExpect(status().isInternalServerError());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM notification_announcement_user_states",
                                Long.class))
                .isZero();
        org.mockito.Mockito.verifyNoInteractions(streams);
    }

    @Test
    void concurrentReadDeleteAndWithdrawalNeverRestoreVisibility() throws Exception {
        long id = publish();
        try (var pool = Executors.newFixedThreadPool(3)) {
            var start = new CountDownLatch(1);
            var a =
                    pool.submit(
                            () -> {
                                start.await();
                                return read(owner, id).andReturn().getResponse().getStatus();
                            });
            var b =
                    pool.submit(
                            () -> {
                                start.await();
                                return remove(owner, id).andReturn().getResponse().getStatus();
                            });
            var c =
                    pool.submit(
                            () -> {
                                start.await();
                                return withdraw(id).andReturn().getResponse().getStatus();
                            });
            start.countDown();
            assertThat(a.get(15, TimeUnit.SECONDS)).isIn(204, 404);
            assertThat(b.get(15, TimeUnit.SECONDS)).isIn(204, 404);
            assertThat(c.get(15, TimeUnit.SECONDS)).isEqualTo(200);
        }
        unread(owner, 0);
        inbox(owner, "SYSTEM", 1, 20).andExpect(jsonPath("$.data.total").value(0));
    }

    /** 两个登录用户均能收到发布/撤回提示，事件不得携带公告正文。 */
    @Test
    void globalEventsReachBothUsersAfterCommit() throws Exception {
        var left =
                mvc.perform(
                                get("/api/me/notifications/events")
                                        .header("Authorization", "Bearer " + owner))
                        .andExpect(status().isOk())
                        .andReturn();
        var right =
                mvc.perform(
                                get("/api/me/notifications/events")
                                        .header("Authorization", "Bearer " + other))
                        .andExpect(status().isOk())
                        .andReturn();
        long id = publish();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while ((!left.getResponse().getContentAsString().contains("notifications-changed")
                        || !right.getResponse()
                                .getContentAsString()
                                .contains("notifications-changed"))
                && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(left.getResponse().getContentAsString())
                .contains("notifications-changed")
                .doesNotContain("正文");
        assertThat(right.getResponse().getContentAsString())
                .contains("notifications-changed")
                .doesNotContain("正文");
        withdraw(id).andExpect(status().isOk());
        when(sessions.isActive(org.mockito.ArgumentMatchers.anyString(), anyLong(), anyLong()))
                .thenReturn(false);
        streams.changed(0);
    }

    /** 跨500条边界，选择后并发删除/撤回的公告不能被全部已读复活。 */
    @Test
    void readAllBatchesAndRechecksConcurrentDeletionAndWithdrawal() throws Exception {
        var args = new java.util.ArrayList<Object[]>();
        for (long i = 10000; i < 10501; i++) {
            args.add(new Object[] {i, i});
        }
        jdbc.batchUpdate(
                "INSERT INTO"
                    + " notification_announcements(id,client_request_id,request_hash,title,content,published_by)"
                    + " VALUES(?,UNHEX(LPAD(HEX(?),32,'0')),UNHEX(REPEAT('aa',32)),'批量公告','正文',22)",
                args);
        var once = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(
                        invocation -> {
                            var ids =
                                    sql.getMapper(
                                                    com.foodmap.notification.mapper
                                                            .AnnouncementMapper.class)
                                            .unreadIds(
                                                    invocation.getArgument(0),
                                                    invocation.getArgument(1));
                            if (once.compareAndSet(false, true)) {
                                try (var pool = Executors.newSingleThreadExecutor()) {
                                    assertThat(
                                                    pool.submit(
                                                                    () ->
                                                                            remove(owner, 10000)
                                                                                    .andReturn()
                                                                                    .getResponse()
                                                                                    .getStatus())
                                                            .get(10, TimeUnit.SECONDS))
                                            .isEqualTo(204);
                                    assertThat(
                                                    pool.submit(
                                                                    () ->
                                                                            withdraw(10001)
                                                                                    .andReturn()
                                                                                    .getResponse()
                                                                                    .getStatus())
                                                            .get(10, TimeUnit.SECONDS))
                                            .isEqualTo(200);
                                }
                            }
                            return ids;
                        })
                .when(announcements)
                .unreadIds(anyLong(), anyLong());
        mvc.perform(
                        post("/api/me/notifications/read-all")
                                .param("category", "ALL")
                                .header("Authorization", "Bearer " + owner))
                .andExpect(status().isNoContent());
        unread(owner, 0);
        inbox(owner, "SYSTEM", 1, 20).andExpect(jsonPath("$.data.total").value(499));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT read_at FROM notification_announcement_user_states WHERE"
                                    + " user_id=11 AND announcement_id=10000",
                                java.sql.Timestamp.class))
                .isNull();
        org.mockito.Mockito.verify(announcements, org.mockito.Mockito.times(3))
                .unreadIds(org.mockito.ArgumentMatchers.eq(11L), anyLong());
    }

    @Test
    void adminListFilterAndDifferentAdminWithdrawalKeepOriginalActor() throws Exception {
        long one = publish(), two = publish();
        withdraw(one).andExpect(status().isOk());
        mvc.perform(
                        get("/api/admin/announcements")
                                .param("status", "WITHDRAWN")
                                .param("pageSize", "1")
                                .header("Authorization", "Bearer " + other))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(Long.toString(one)));
        jdbc.update(
                "INSERT INTO user_roles(user_id,role_id) SELECT 11,id FROM roles WHERE"
                    + " code='ROLE_ADMIN'");
        mvc.perform(
                        post("/api/admin/announcements/" + one + "/withdraw")
                                .header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.withdrawnBy").value("22"))
                .andExpect(jsonPath("$.data.replayed").value(true));
        mvc.perform(
                        get("/api/admin/announcements/" + one)
                                .header("Authorization", "Bearer " + other))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value("正文"));
        for (String id : List.of("0", "01", "-1", "9223372036854775808", "nope")) {
            mvc.perform(
                            get("/api/me/announcements/" + id)
                                    .header("Authorization", "Bearer " + owner))
                    .andExpect(status().isBadRequest());
            mvc.perform(
                            delete("/api/me/notifications/announcement:" + id)
                                    .header("Authorization", "Bearer " + owner))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(
                        get("/api/admin/announcements")
                                .param("status", "DRAFT")
                                .header("Authorization", "Bearer " + other))
                .andExpect(status().isBadRequest());
    }

    @Test
    void adminListCombinesStatusAndAudienceBeforePaginationAndLimitsRecipientPreview()
            throws Exception {
        jdbc.update("INSERT INTO users(id,username,email,password_hash,email_verified) VALUES(?,?,?,?,TRUE)",
                33, "notice_33", "notice33@example.test", "test-only");
        long all = publish();
        String targeted = id(publishTargeted(other, UUID.randomUUID(), List.of("33", "22", "11"))
                .andExpect(status().isOk()));
        String withdrawn = id(publishTargeted(other, UUID.randomUUID(), List.of("33"))
                .andExpect(status().isOk()));
        withdraw(Long.parseLong(withdrawn)).andExpect(status().isOk());
        var list = "/api/admin/announcements";
        var authorization = "Bearer " + other;

        mvc.perform(get(list).header("Authorization", authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pageSize").value(10))
                .andExpect(jsonPath("$.data.total").value(3));
        mvc.perform(get(list).header("Authorization", authorization)
                        .param("audience", "TARGETED").param("status", "PUBLISHED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(targeted))
                .andExpect(jsonPath("$.data.records[0].recipientCount").value(3))
                .andExpect(jsonPath("$.data.records[0].recipientPreview.length()").value(2))
                .andExpect(jsonPath("$.data.records[0].recipientPreview[0].username").value("notice_11"))
                .andExpect(jsonPath("$.data.records[0].recipientPreview[1].id").value("22"));
        mvc.perform(get(list).header("Authorization", authorization)
                        .param("audience", "TARGETED").param("pageSize", "1").param("page", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.records[0].id").value(targeted));
        mvc.perform(get(list).header("Authorization", authorization).param("audience", "ALL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(Long.toString(all)))
                .andExpect(jsonPath("$.data.records[0].recipientCount").value(0))
                .andExpect(jsonPath("$.data.records[0].recipientPreview").isEmpty());
        mvc.perform(get(list).header("Authorization", authorization).param("audience", "OTHER"))
                .andExpect(status().isBadRequest());
    }

    private UUID lastRequest;

    private long publish() throws Exception {
        lastRequest = UUID.randomUUID();
        return Long.parseLong(
                id(publish(other, lastRequest, "标题", "正文").andExpect(status().isOk())));
    }

    private String id(ResultActions result) throws Exception {
        return new tools.jackson.databind.ObjectMapper()
                .readTree(result.andReturn().getResponse().getContentAsString())
                .get("data")
                .get("id")
                .asText();
    }

    private String body(UUID uuid, String title, String content) throws Exception {
        return new tools.jackson.databind.ObjectMapper()
                .writeValueAsString(
                        java.util.Map.of(
                                "clientRequestId",
                                uuid.toString(),
                                "title",
                                title,
                                "content",
                                content));
    }

    private ResultActions publish(String token, UUID uuid, String title, String content)
            throws Exception {
        return mvc.perform(
                post("/api/admin/announcements")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(body(uuid, title, content)));
    }

    @Test
    void manualRewardsArePrivateAndSurviveAnnouncementWithdrawal() throws Exception {
        UUID requestId = UUID.randomUUID();
        var draft = new java.util.HashMap<String, Object>(java.util.Map.of(
                "clientRequestId", requestId.toString(), "title", "活动奖励", "content", "感谢参与",
                "recipientUserIds", List.of("11", "22"), "rewardRegistration",
                java.util.Map.of("type", "OTHER", "note", "  内部登记  ")));
        var json = new tools.jackson.databind.ObjectMapper();
        String body = json.writeValueAsString(draft);
        String announcementId = id(sendWithReward("/api/admin/announcements/targeted", body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rewardRegistration").doesNotExist()));
        sendWithReward("/api/admin/announcements/targeted", body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replayed").value(true));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_manual_reward_records", Long.class))
                .isEqualTo(2);

        draft.put("rewardRegistration", java.util.Map.of("type", "OTHER", "note", "另一个说明"));
        sendWithReward("/api/admin/announcements/targeted", json.writeValueAsString(draft))
                .andExpect(status().isConflict());
        draft.put("clientRequestId", UUID.randomUUID().toString());
        draft.put("rewardRegistration", java.util.Map.of("type", "OTHER", "note", " \t"));
        sendWithReward("/api/admin/announcements/targeted", json.writeValueAsString(draft))
                .andExpect(status().isBadRequest());
        draft.put("rewardRegistration", java.util.Map.of("type", "FIRST_REVIEW"));
        draft.remove("recipientUserIds");
        sendWithReward("/api/admin/announcements", json.writeValueAsString(draft))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_announcements", Long.class))
                .isEqualTo(1);

        mvc.perform(get("/api/me/announcements/" + announcementId).header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value("感谢参与"))
                .andExpect(jsonPath("$.data.rewardNote").doesNotExist())
                .andExpect(jsonPath("$.data.rewardType").doesNotExist());
        mvc.perform(get("/api/admin/users/11/rewards").header("Authorization", "Bearer " + owner))
                .andExpect(status().isForbidden());

        withdraw(Long.parseLong(announcementId)).andExpect(status().isOk());
        mvc.perform(get("/api/admin/users/11/rewards").header("Authorization", "Bearer " + other))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].rewardType").value("OTHER"))
                .andExpect(jsonPath("$.data.records[0].rewardNote").value("内部登记"))
                .andExpect(jsonPath("$.data.records[0].sourceAnnouncementId").value(announcementId))
                .andExpect(jsonPath("$.data.records[0].sourceAnnouncementTitle").value("活动奖励"))
                .andExpect(jsonPath("$.data.records[0].sourceWithdrawn").value(true));
    }

    private ResultActions sendWithReward(String path, String body) throws Exception {
        return mvc.perform(post(path).header("Authorization", "Bearer " + other)
                .contentType("application/json").content(body));
    }

    @Test
    void directoryShowsOldestRewardTypesBeforeLaterRegistrations() throws Exception {
        var types = List.of("FIRST_REVIEW", "REGISTRATION_INVITE", "OTHER", "FIRST_REVIEW",
                "OTHER", "REGISTRATION_INVITE", "FIRST_REVIEW");
        // 模拟先登记近期奖励、后来才补登记历史奖励。
        for (int index = types.size() - 1; index >= 0; index--) {
            long announcement = Long.parseLong(id(publishTargeted(other, UUID.randomUUID(), List.of("11"))
                    .andExpect(status().isOk())));
            jdbc.update("UPDATE notification_announcements SET published_at = ? WHERE id = ?",
                    "2026-11-" + (10 + index) + " 00:00:00", announcement);
            jdbc.update("""
                    INSERT INTO user_manual_reward_records
                        (user_id, reward_type, reward_note, source_announcement_id, recorded_by)
                    VALUES (11, ?, 'test fixture', ?, 22)
                    """, types.get(index), announcement);
        }

        mvc.perform(get("/api/admin/users").param("keyword", "notice_11")
                        .header("Authorization", "Bearer " + other))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].rewardSummary.types")
                        .value(org.hamcrest.Matchers.contains(types.subList(0, 5).toArray(String[]::new))))
                .andExpect(jsonPath("$.data.records[0].rewardSummary.hasMore").value(true));
        mvc.perform(get("/api/admin/users").param("keyword", "notice_22")
                        .header("Authorization", "Bearer " + other))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].rewardSummary.types").isEmpty())
                .andExpect(jsonPath("$.data.records[0].rewardSummary.hasMore").value(false));
    }

    private ResultActions publishTargeted(String token, UUID uuid, List<String> recipients)
            throws Exception {
        String request = new tools.jackson.databind.ObjectMapper().writeValueAsString(
                java.util.Map.of("clientRequestId", uuid.toString(), "title", "标题",
                        "content", "正文", "recipientUserIds", recipients));
        return mvc.perform(post("/api/admin/announcements/targeted")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(request));
    }

    private ResultActions withdraw(long id) throws Exception {
        return mvc.perform(
                post("/api/admin/announcements/" + id + "/withdraw")
                        .header("Authorization", "Bearer " + other));
    }

    private ResultActions setMuted(String token, long user, boolean muted) throws Exception {
        return mvc.perform(put("/api/admin/users/" + user + "/mute")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content("{\"muted\":" + muted + "}"));
    }

    private ResultActions read(String token, long id) throws Exception {
        return mvc.perform(
                post("/api/me/notifications/announcement:" + id + "/read")
                        .header("Authorization", "Bearer " + token));
    }

    private ResultActions remove(String token, long id) throws Exception {
        return mvc.perform(
                delete("/api/me/notifications/announcement:" + id)
                        .header("Authorization", "Bearer " + token));
    }

    private ResultActions inbox(String token, String category, int page, int size)
            throws Exception {
        return mvc.perform(
                        get("/api/me/notifications")
                                .param("category", category)
                                .param("page", Integer.toString(page))
                                .param("pageSize", Integer.toString(size))
                                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    private void unread(String token, long count) throws Exception {
        mvc.perform(get("/api/me/notifications/unread").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unreadCount").value(count));
    }

    private String token(long user) {
        var jwt = authentication.getJwt();
        var now = Instant.now();
        var claims =
                JwtClaimsSet.builder()
                        .issuer(jwt.getIssuer())
                        .audience(List.of(jwt.getAudience()))
                        .subject(Long.toString(user))
                        .issuedAt(now.minusSeconds(30))
                        .expiresAt(now.plusSeconds(600))
                        .id(UUID.randomUUID().toString())
                        .claim("credential_version", 0)
                        .build();
        return encoder.encode(
                        JwtEncoderParameters.from(
                                JwsHeader.with(SignatureAlgorithm.RS256).build(), claims))
                .getTokenValue();
    }

    private static String password() {
        return System.getenv().getOrDefault("REVIEW_TEST_DB_PASSWORD", "");
    }

    private static String url(String name) {
        String server = System.getenv("REVIEW_TEST_DB_SERVER");
        if (server == null
                || !server.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]{1,5}/")) {
            throw new IllegalArgumentException("Expected explicit loopback test server");
        }
        return server
                + name
                + "?serverTimezone=UTC&forceConnectionTimeZoneToSession=true&useSSL=false&allowPublicKeyRetrieval=true";
    }

    private static Connection connect(String name) throws SQLException {
        return DriverManager.getConnection(url(name), "root", password());
    }

    private static void drop(String name) throws SQLException {
        if (!name.matches("foodmap_announcement_api_it_[a-f0-9]{32}")) {
            throw new IllegalStateException("Unexpected test database");
        }
        try (var connection = connect("");
                var statement = connection.createStatement()) {
            statement.executeUpdate("DROP DATABASE `" + name + "`");
        }
    }
}
