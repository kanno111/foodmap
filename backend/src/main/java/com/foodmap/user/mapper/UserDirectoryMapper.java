package com.foodmap.user.mapper;

import com.foodmap.user.dto.UserDirectoryEntry;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserDirectoryMapper {
    @Select("""
            SELECT u.id, u.username, u.avatar_file_id IS NOT NULL AS has_avatar, u.muted_at,
                u.created_at, u.status,
            """ + UserMuteMapper.MODERATABLE + """
            AS can_moderate FROM users u WHERE u.id = #{id} AND u.status != 'DELETED'
            """)
    UserDirectoryEntry find(long id);

    @Select("""
            <script>
            SELECT u.id, u.username, u.avatar_file_id IS NOT NULL AS has_avatar, u.muted_at,
            """ + UserMuteMapper.MODERATABLE + """
            AS can_moderate FROM users u WHERE u.status != 'DELETED'
            <if test="pattern != null">AND username LIKE #{pattern} ESCAPE '!'</if>
            </script>
            """)
    List<UserDirectoryEntry> list(@Param("pattern") String pattern);

    @Select("""
            <script>
            SELECT id, username FROM users WHERE status = 'ACTIVE' AND email_verified = TRUE
            <if test="pattern != null">AND username LIKE #{pattern} ESCAPE '!'</if>
            ORDER BY username, id LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<UserDirectoryEntry> searchActive(
            @Param("pattern") String pattern, @Param("offset") long offset, @Param("limit") int limit);

    @Select("""
            <script>
            SELECT id, username FROM users WHERE status != 'DELETED' AND id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<UserDirectoryEntry> namesByIds(@Param("ids") List<Long> ids);

    @Select("""
            <script>
            SELECT id FROM users WHERE status = 'ACTIVE' AND email_verified = TRUE AND id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            ORDER BY id FOR SHARE
            </script>
            """)
    List<Long> lockActiveIds(@Param("ids") List<Long> ids);
}
