package com.foodmap.user.mapper;

import com.foodmap.user.entity.AdminInviteCodeEntity;
import com.foodmap.user.entity.AdminInviteRecord;
import com.foodmap.user.domain.AdminInviteStatus;
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
