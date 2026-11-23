package com.foodmap.user.mapper;

import com.foodmap.user.dto.UserDirectoryEntry;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserDirectoryMapper {
    @Select("""
            <script>
            SELECT id, username, avatar_file_id IS NOT NULL AS has_avatar
            FROM users WHERE status != 'DELETED'
            <if test="pattern != null">AND username LIKE #{pattern} ESCAPE '!'</if>
            </script>
            """)
    List<UserDirectoryEntry> list(@Param("pattern") String pattern);
}
