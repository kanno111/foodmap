package com.foodmap.user.mapper;

import com.foodmap.user.entity.AdminInviteCodeEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Optional;

@Mapper
public interface UserInviteCodeMapper {
    @Insert("""
            INSERT INTO user_invite_codes
                (type, code_hash, issued_by_admin_id, bound_email, created_at, expires_at)
            VALUES ('ADMIN_SINGLE_USE', #{codeHash}, #{issuedByAdminId}, #{boundEmail},
                #{createdAt}, #{expiresAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertAdminCode(AdminInviteCodeEntity invite);

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
}
