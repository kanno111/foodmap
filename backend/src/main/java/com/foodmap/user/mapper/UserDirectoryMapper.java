package com.foodmap.user.mapper;

import com.foodmap.user.dto.UserDirectoryEntry;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserDirectoryMapper {
    String FILTER = """
            FROM users WHERE status != 'DELETED'
            <if test="pattern != null">AND username LIKE #{pattern} ESCAPE '!'</if>
            """;

    @Select("<script>SELECT COUNT(*) " + FILTER + "</script>")
    long count(@Param("pattern") String pattern);

    @Select("""
            <script>
            SELECT id,username,avatar_file_id IS NOT NULL AS has_avatar
            """ + FILTER + """
            ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<UserDirectoryEntry> page(
            @Param("pattern") String pattern, @Param("limit") int limit, @Param("offset") long offset);
}
