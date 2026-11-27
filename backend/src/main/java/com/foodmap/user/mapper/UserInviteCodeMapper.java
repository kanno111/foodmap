package com.foodmap.user.mapper;

import com.foodmap.user.entity.AdminInviteCodeEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Optional;

@Mapper
public interface UserInviteCodeMapper {
    String ACTIVE_ADMIN_CODE = """
            type = 'ADMIN_SINGLE_USE' AND CAST(bound_email AS BINARY) = CAST(#{email} AS BINARY)
                AND revoked_at IS NULL AND used_at IS NULL
                AND created_at <= CURRENT_TIMESTAMP(6) AND expires_at > CURRENT_TIMESTAMP(6)
            """;

    @Insert("""
            INSERT INTO user_invite_codes
                (type, code_hash, issued_by_admin_id, bound_email, expires_at)
            VALUES ('ADMIN_SINGLE_USE', #{codeHash}, #{issuedByAdminId}, #{boundEmail},
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

    @Select("SELECT id FROM user_invite_codes WHERE code_hash = #{hash} AND " + ACTIVE_ADMIN_CODE)
    Optional<Long> findActiveAdminId(@Param("hash") byte[] hash, @Param("email") String email);

    @Update("UPDATE user_invite_codes SET used_at = CURRENT_TIMESTAMP(6) WHERE id = #{id} AND "
            + ACTIVE_ADMIN_CODE)
    int consumeAdminCode(@Param("id") long id, @Param("email") String email);

    @Insert("""
            INSERT INTO user_invite_redemptions (invitee_user_id, invite_code_id)
            VALUES (#{inviteeId}, #{inviteId})
            """)
    int recordRedemption(@Param("inviteeId") long inviteeId, @Param("inviteId") long inviteId);
}
