package com.foodmap.user.mapper;

import com.foodmap.user.dto.UserDirectoryEntry;
import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserMuteMapper {
    String MODERATABLE = """
            (u.status = 'ACTIVE' AND u.email_verified = TRUE AND NOT EXISTS (
                SELECT 1 FROM user_roles ur JOIN roles r ON r.id = ur.role_id
                WHERE ur.user_id = u.id AND r.code = 'ROLE_ADMIN'))
            """;

    @Select("SELECT u.id, u.muted_at FROM users u WHERE u.id = #{id} AND "
            + MODERATABLE + " FOR UPDATE")
    UserDirectoryEntry lockTarget(long id);

    @Update("""
            UPDATE users SET muted_at = IF(#{muted}, CURRENT_TIMESTAMP(6), NULL)
            WHERE id = #{id}
            """)
    int setMuted(@Param("id") long id, @Param("muted") boolean muted);

    @Select("SELECT muted_at FROM users WHERE id = #{id}")
    Instant mutedAt(long id);

    @Select("SELECT id FROM users WHERE id = #{id} AND muted_at IS NULL FOR SHARE")
    Long lockUnmuted(long id);
}
