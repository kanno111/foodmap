package com.foodmap.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.foodmap.common.exception.BusinessException;
import com.foodmap.user.config.UserRegistrationProperties;
import com.foodmap.user.dto.AdminInviteCreateRequest;
import com.foodmap.user.exception.UserErrorCode;
import com.foodmap.user.mapper.UserInviteCodeMapper;
import com.foodmap.user.policy.UserInviteCodeGenerator;
import com.foodmap.user.policy.UserRegistrationPolicy;
import com.foodmap.user.service.UserInviteCodeService;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 在显式指定的回环服务器上，用随机隔离库验证 V55 到 V56 的升级及表约束。 */
@EnabledIfSystemProperty(named = "invites.integration", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserInviteSchemaIntegrationTest {
    private static final String MIGRATION = "db/migration/V56__create_user_invites.sql";
    private String database;
    private boolean created;
    private DriverManagerDataSource dataSource;
    private JdbcTemplate db;
    private UserInviteCodeMapper inviteMapper;
    private List<Map<String, Object>> usersBefore;
    private List<Map<String, Object>> rolesBefore;
    private int migrationsExecuted;

    @BeforeAll
    void migrate() throws SQLException {
        database = "foodmap_user_invites_it_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = dataSource("").getConnection();
                var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE DATABASE `" + database
                    + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            created = true;
        }
        dataSource = dataSource(database);
        db = new JdbcTemplate(dataSource);
        var configuration = new Configuration(
                new Environment("test", new SpringManagedTransactionFactory(), dataSource));
        configuration.addMapper(UserInviteCodeMapper.class);
        inviteMapper = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration))
                .getMapper(UserInviteCodeMapper.class);
        flyway("55").migrate();
        db.update("""
                INSERT INTO users(id, username, email, password_hash, email_verified) VALUES
                    (11, 'invite_admin', 'admin@example.test', 'fixture-hash', TRUE),
                    (22, 'referral_owner', 'owner@example.test', 'fixture-hash', TRUE),
                    (33, 'new_student', 'student@example.test', 'fixture-hash', TRUE),
                    (44, 'another_student', 'student2@example.test', 'fixture-hash', TRUE)
                """);
        db.update("""
                INSERT INTO user_roles(user_id, role_id)
                SELECT 11, id FROM roles WHERE code = 'ROLE_ADMIN'
                """);
        usersBefore = db.queryForList("SELECT * FROM users ORDER BY id");
        rolesBefore = db.queryForList("SELECT * FROM user_roles ORDER BY user_id, role_id");
        migrationsExecuted = flyway("56").migrate().migrationsExecuted;
    }

    @BeforeEach
    void clearInvites() {
        db.update("DELETE FROM user_invite_redemptions");
        db.update("DELETE FROM user_invite_codes");
    }

    @AfterAll
    void dropIsolatedDatabase() throws SQLException {
        if (!created) {
            return;
        }
        if (!database.matches("foodmap_user_invites_it_[a-f0-9]{32}")) {
            throw new IllegalStateException("Expected an isolated user invitation test database");
        }
        try (var connection = dataSource("").getConnection();
                var statement = connection.createStatement()) {
            statement.executeUpdate("DROP DATABASE `" + database + "`");
        }
    }

    @Test
    void upgradePreservesV55AccountsAndRolesAndCanSafelyRunAgain() {
        assertThat(migrationsExecuted).isEqualTo(1);
        assertThat(db.queryForList("SELECT * FROM users ORDER BY id")).isEqualTo(usersBefore);
        assertThat(db.queryForList("SELECT * FROM user_roles ORDER BY user_id, role_id"))
                .isEqualTo(rolesBefore);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM user_invite_codes", Long.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM user_invite_redemptions", Long.class))
                .isZero();

        addReferral(1, "fixture-referral", 22);
        redeem(33, 1);
        var record = db.queryForMap("SELECT * FROM user_invite_redemptions WHERE invitee_user_id=33");
        new ResourceDatabasePopulator(new ClassPathResource(MIGRATION)).execute(dataSource);
        assertThat(flyway("56").migrate().migrationsExecuted).isZero();
        assertThat(db.queryForMap("SELECT * FROM user_invite_redemptions WHERE invitee_user_id=33"))
                .isEqualTo(record);
    }

    @Test
    void acceptsBothCodeTypesAndEnforcesUniqueHashAndReferralOwner() {
        addAdmin(1, "fixture-admin-one");
        addAdmin(2, "fixture-admin-two");
        addReferral(3, "fixture-referral", 22);
        assertThatThrownBy(() -> addReferral(4, "fixture-second-referral", 22))
                .isInstanceOf(DuplicateKeyException.class)
                .hasStackTraceContaining("uk_user_invite_codes_owner");
        assertThatThrownBy(() -> addReferral(5, "fixture-admin-one", 33))
                .isInstanceOf(DuplicateKeyException.class)
                .hasStackTraceContaining("uk_user_invite_codes_hash");
        assertThat(db.queryForObject(
                "SELECT public_code FROM user_invite_codes WHERE id=3", String.class))
                .isEqualTo("fixture-referral");
    }

    @Test
    void rejectsInvalidCodeTypeFieldsAndLifecycle() {
        addAdmin(1, "fixture-admin-one");
        addAdmin(2, "fixture-admin-two");
        addReferral(3, "fixture-referral", 22);
        for (String assignment : List.of(
                "type='ADMIN_SINGLE_USE '", "public_code='visible'", "issued_by_admin_id=NULL",
                "owner_user_id=33", "bound_email=NULL", "bound_email='   '", "expires_at=NULL")) {
            rejectsUpdate(1, assignment, "chk_user_invite_codes_type_fields");
        }
        for (String assignment : List.of(
                "owner_user_id=NULL", "public_code='   '", "expires_at=created_at+INTERVAL 1 DAY",
                "used_at=created_at", "revoked_at=created_at")) {
            rejectsUpdate(3, assignment, "chk_user_invite_codes_type_fields");
        }
        for (String assignment : List.of(
                "expires_at=created_at", "used_at=expires_at", "revoked_at=created_at-INTERVAL 1 SECOND",
                "revoked_at=created_at, used_at=created_at")) {
            rejectsUpdate(1, assignment, "chk_user_invite_codes_lifecycle");
        }
        assertThat(db.update("UPDATE user_invite_codes SET used_at=created_at WHERE id=1"))
                .isEqualTo(1);
        assertThat(db.update("UPDATE user_invite_codes SET revoked_at=created_at WHERE id=2"))
                .isEqualTo(1);
    }

    @Test
    void recordsMultipleReferralsButKeepsOneAttributionPerUserAndEnforcesForeignKeys() {
        addAdmin(1, "fixture-admin");
        addReferral(2, "fixture-referral", 22);
        redeem(33, 2);
        redeem(44, 2);
        assertThat(db.queryForList("""
                SELECT invitee_user_id FROM user_invite_redemptions
                WHERE invite_code_id=2 ORDER BY redeemed_at, invitee_user_id
                """, Long.class)).containsExactly(33L, 44L);
        assertThatThrownBy(() -> redeem(33, 1)).isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() -> redeem(999, 2)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> redeem(11, 999)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> addReferral(3, "fixture-invalid-owner", 999))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> db.update("DELETE FROM user_invite_codes WHERE id=2"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> db.update("DELETE FROM users WHERE id=33"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void createsAdminInviteWithDigestOnlyAndReturnsItsExpiry() {
        var request = new AdminInviteCreateRequest();
        request.setEmail("Alumnus@Example.TEST");
        var service = inviteService(new UserInviteCodeGenerator());

        var created = service.createAdminCode(11, request);

        assertThat(created.getCode()).matches("[0-9A-HJKMNP-TV-Z]{26}");
        assertThat(db.queryForObject("""
                SELECT COUNT(*) FROM user_invite_codes
                WHERE id=? AND type='ADMIN_SINGLE_USE' AND public_code IS NULL
                    AND issued_by_admin_id=11 AND bound_email='alumnus@example.test'
                    AND code_hash=UNHEX(SHA2(?, 256)) AND expires_at=created_at+INTERVAL 7 DAY
                """, Long.class, Long.parseLong(created.getId()), created.getCode())).isEqualTo(1L);
        assertThat(db.queryForObject("""
                SELECT CAST(UNIX_TIMESTAMP(expires_at)*1000000 AS UNSIGNED)
                FROM user_invite_codes WHERE id=?
                """, Long.class, Long.parseLong(created.getId())))
                .isEqualTo(ChronoUnit.MICROS.between(Instant.EPOCH, created.getExpiresAt()));

        request.setValidDays(2);
        var second = service.createAdminCode(11, request);
        assertThat(second.getCode()).isNotEqualTo(created.getCode());
        assertThat(db.queryForObject("""
                SELECT COUNT(*) FROM user_invite_codes
                WHERE id=? AND expires_at=created_at+INTERVAL 2 DAY
                """, Long.class, Long.parseLong(second.getId()))).isEqualTo(1L);

        request.setEmail("alumnus@STD.UESTC.EDU.CN");
        assertThatThrownBy(() -> service.createAdminCode(11, request))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(UserErrorCode.PERSONAL_EMAIL_REQUIRED));
    }

    @Test
    void concurrentReferralRequestsReturnTheSameStoredCode() throws Exception {
        var barrier = new CyclicBarrier(2);
        var generator = mock(UserInviteCodeGenerator.class);
        var realGenerator = new UserInviteCodeGenerator();
        when(generator.newReferralCode()).thenAnswer(call -> {
            barrier.await(5, TimeUnit.SECONDS);
            return realGenerator.newReferralCode();
        });
        when(generator.hash(anyString()))
                .thenAnswer(call -> realGenerator.hash(call.getArgument(0)));
        var service = inviteService(generator);

        try (var requests = Executors.newFixedThreadPool(2)) {
            var first = requests.submit(() -> service.getOrCreateReferralCode(22));
            var second = requests.submit(() -> service.getOrCreateReferralCode(22));
            String code = first.get(10, TimeUnit.SECONDS).getCode();

            assertThat(code).matches("[0-9A-HJKMNP-TV-Z]{16}");
            assertThat(second.get(10, TimeUnit.SECONDS).getCode()).isEqualTo(code);
            assertThat(service.getOrCreateReferralCode(22).getCode()).isEqualTo(code);
            assertThat(db.queryForList("""
                    SELECT public_code FROM user_invite_codes
                    WHERE owner_user_id=22 AND type='USER_REFERRAL' AND code_hash=UNHEX(SHA2(public_code, 256))
                    """, String.class)).containsExactly(code);
        }
    }

    @Test
    void retriesWhenAnotherOwnersCodeHasTheSameDigest() {
        String occupied = "123456789ABCDEFG";
        String available = "HJKMNPQRSTVWXYZ0";
        addReferral(1, occupied, 22);
        var generator = mock(UserInviteCodeGenerator.class);
        var realGenerator = new UserInviteCodeGenerator();
        when(generator.newReferralCode()).thenReturn(occupied, available);
        when(generator.hash(occupied)).thenReturn(realGenerator.hash(occupied));
        when(generator.hash(available)).thenReturn(realGenerator.hash(available));

        assertThat(inviteService(generator).getOrCreateReferralCode(33).getCode()).isEqualTo(available);
        assertThat(inviteMapper.findReferralCode(22)).contains(occupied);
    }

    private UserInviteCodeService inviteService(UserInviteCodeGenerator generator) {
        var properties = new UserRegistrationProperties();
        properties.setAllowedEmailDomains(List.of("std.uestc.edu.cn"));
        return new UserInviteCodeService(inviteMapper, generator, new UserRegistrationPolicy(properties));
    }

    private void addAdmin(long id, String code) {
        db.update("""
                INSERT INTO user_invite_codes(id, type, code_hash, issued_by_admin_id, bound_email, expires_at)
                VALUES (?, 'ADMIN_SINGLE_USE', UNHEX(SHA2(?, 256)), 11, 'alumnus@example.test',
                    CURRENT_TIMESTAMP(6) + INTERVAL 7 DAY)
                """, id, code);
    }

    private void addReferral(long id, String code, long owner) {
        db.update("""
                INSERT INTO user_invite_codes(id, type, code_hash, public_code, owner_user_id)
                VALUES (?, 'USER_REFERRAL', UNHEX(SHA2(?, 256)), ?, ?)
                """, id, code, code, owner);
    }

    private void redeem(long user, long invite) {
        db.update("INSERT INTO user_invite_redemptions(invitee_user_id, invite_code_id) VALUES (?, ?)",
                user, invite);
    }

    private void rejectsUpdate(long id, String assignment, String constraint) {
        assertThatThrownBy(() -> db.update("UPDATE user_invite_codes SET " + assignment + " WHERE id=?", id))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining(constraint);
    }

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    private static DriverManagerDataSource dataSource(String database) {
        String server = System.getenv("INVITES_TEST_DB_SERVER");
        if (server == null || !server.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]{1,5}/")) {
            throw new IllegalArgumentException("Expected an explicit loopback MySQL test server");
        }
        return new DriverManagerDataSource(
                server + database
                        + "?serverTimezone=UTC&forceConnectionTimeZoneToSession=true&useSSL=false"
                        + "&allowPublicKeyRetrieval=true",
                System.getenv().getOrDefault("INVITES_TEST_DB_USERNAME", "root"),
                System.getenv().getOrDefault("INVITES_TEST_DB_PASSWORD", ""));
    }
}
