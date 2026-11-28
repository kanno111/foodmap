package com.foodmap.user.mapper;

import com.foodmap.user.entity.AdminInviteCodeEntity;
import com.foodmap.user.entity.AdminInviteRecord;
import com.foodmap.user.domain.AdminInviteStatus;
import com.foodmap.user.dto.UserReferralView;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Optional;
import java.util.List;

@Mapper
public interface UserInviteCodeMapper {
    record ReferralCount(long userId, long total) {}

    @Select("""
            <script>
            SELECT c.owner_user_id AS user_id, COUNT(*) AS total
            FROM user_invite_codes c JOIN user_invite_redemptions r ON r.invite_code_id = c.id
            WHERE c.type = 'USER_REFERRAL' AND c.owner_user_id IN
            <foreach collection="ids" item="id" open="(" close=")" separator=",">#{id}</foreach>
            GROUP BY c.owner_user_id
            </script>
            """)
    List<ReferralCount> referralCounts(@Param("ids") List<Long> ids);

    @Select("""
            SELECT CASE WHEN c.type = 'USER_REFERRAL' THEN 'REFERRAL' ELSE 'ADMIN_INVITE' END AS kind,
                u.username AS inviter_name
            FROM user_invite_redemptions r
            JOIN user_invite_codes c ON c.id = r.invite_code_id
            LEFT JOIN users u ON u.id = c.owner_user_id AND u.status != 'DELETED'
            WHERE r.invitee_user_id = #{user}
            """)
    UserReferralView.RegistrationSource registrationSource(long user);

    @Select("""
            SELECT u.id, CASE WHEN u.status = 'DELETED' THEN '账号已注销' ELSE u.username END AS username,
                (u.status != 'DELETED' AND u.avatar_file_id IS NOT NULL) AS has_avatar,
                u.created_at AS registered_at, u.status != 'DELETED' AS profile_available
            FROM user_invite_codes c JOIN user_invite_redemptions r ON r.invite_code_id = c.id
            JOIN users u ON u.id = r.invitee_user_id
            WHERE c.type = 'USER_REFERRAL' AND c.owner_user_id = #{user}
            ORDER BY r.redeemed_at DESC, r.invitee_user_id DESC LIMIT #{limit} OFFSET #{offset}
            """)
    List<UserReferralView.Invitee> invitees(
            @Param("user") long user, @Param("limit") int limit, @Param("offset") long offset);

    String ACTIVE_ADMIN_CODE = """
            type = 'ADMIN_SINGLE_USE' AND revoked_at IS NULL AND used_at IS NULL
                AND created_at <= CURRENT_TIMESTAMP(6) AND expires_at > CURRENT_TIMESTAMP(6)
            """;
    String EMAIL_MATCH = "(bound_email IS NULL OR CAST(bound_email AS BINARY) = CAST(#{email} AS BINARY))";
    String ADMIN_STATUS = """
            CASE WHEN c.used_at IS NOT NULL THEN 'USED'
                 WHEN c.revoked_at IS NOT NULL THEN 'REVOKED'
                 WHEN c.expires_at <= CURRENT_TIMESTAMP(6) THEN 'EXPIRED'
                 ELSE 'AVAILABLE' END
            """;

    @Insert("""
            INSERT INTO user_invite_codes
                (type, code_hash, issued_by_admin_id, bound_email, expires_at)
            VALUES ('ADMIN_SINGLE_USE', #{codeHash}, #{issuedByAdminId}, #{boundEmail,jdbcType=VARCHAR},
                TIMESTAMPADD(DAY, #{validDays}, CURRENT_TIMESTAMP(6)))
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertAdminCode(AdminInviteCodeEntity invite);

    @Select("""
            SELECT CAST(UNIX_TIMESTAMP(expires_at) * 1000000 AS UNSIGNED)
            FROM user_invite_codes WHERE id = #{id}
            """)
    long findAdminExpiryMicros(@Param("id") long id);

    @Select("""
            SELECT public_code FROM user_invite_codes
            WHERE owner_user_id = #{ownerUserId} AND type = 'USER_REFERRAL'
            """)
    Optional<String> findReferralCode(@Param("ownerUserId") long ownerUserId);

    @Insert("""
            INSERT INTO user_invite_codes (type, code_hash, public_code, owner_user_id)
            VALUES ('USER_REFERRAL', #{codeHash}, #{code}, #{ownerUserId})
            """)
    int insertReferralCode(@Param("ownerUserId") long ownerUserId,
                           @Param("code") String code, @Param("codeHash") byte[] codeHash);

    @Select("SELECT id FROM user_invite_codes WHERE code_hash = #{hash} AND type = 'USER_REFERRAL'")
    Optional<Long> findReferralId(@Param("hash") byte[] hash);

    @Select("SELECT id FROM user_invite_codes WHERE code_hash = #{hash} AND " + ACTIVE_ADMIN_CODE
            + " AND " + EMAIL_MATCH)
    Optional<Long> findActiveAdminId(@Param("hash") byte[] hash, @Param("email") String email);

    @Update("UPDATE user_invite_codes SET used_at = CURRENT_TIMESTAMP(6) WHERE id = #{id} AND "
            + ACTIVE_ADMIN_CODE + " AND " + EMAIL_MATCH)
    int consumeAdminCode(@Param("id") long id, @Param("email") String email);

    @Insert("""
            INSERT INTO user_invite_redemptions (invitee_user_id, invite_code_id)
            VALUES (#{inviteeId}, #{inviteId})
            """)
    int recordRedemption(@Param("inviteeId") long inviteeId, @Param("inviteId") long inviteId);

    @Select("""
            SELECT c.id, c.bound_email AS boundEmail, u.username AS registeredUsername,
                CASE WHEN u.status != 'DELETED' THEN u.id END AS registeredUserId,
                CAST(UNIX_TIMESTAMP(c.created_at) * 1000000 AS UNSIGNED) AS createdMicros,
                CAST(UNIX_TIMESTAMP(c.expires_at) * 1000000 AS UNSIGNED) AS expiryMicros,
            """ + ADMIN_STATUS + """
                 AS status
            FROM user_invite_codes AS c
            LEFT JOIN user_invite_redemptions AS r ON r.invite_code_id = c.id
            LEFT JOIN users AS u ON u.id = r.invitee_user_id
            WHERE c.type = 'ADMIN_SINGLE_USE'
            """ + " AND (#{status,jdbcType=VARCHAR} IS NULL OR " + ADMIN_STATUS + """
                = #{status,jdbcType=VARCHAR})
            ORDER BY c.id DESC LIMIT #{limit} OFFSET #{offset}
            """)
    List<AdminInviteRecord> pageAdminCodes(@Param("status") AdminInviteStatus status,
            @Param("limit") int limit, @Param("offset") int offset);

    @Update("UPDATE user_invite_codes SET revoked_at = CURRENT_TIMESTAMP(6) WHERE id = #{id} AND "
            + ACTIVE_ADMIN_CODE)
    int revokeAdminCode(@Param("id") long id);

    @Select("SELECT " + ADMIN_STATUS + " FROM user_invite_codes AS c WHERE c.id = #{id} AND c.type = 'ADMIN_SINGLE_USE'")
    Optional<AdminInviteStatus> findAdminStatus(@Param("id") long id);
}
