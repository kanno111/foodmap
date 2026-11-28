package com.foodmap.user.mapper;

import com.foodmap.user.domain.ManualRewardType;
import com.foodmap.user.dto.ManualRewardEntry;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserManualRewardMapper {
    @Insert("""
            <script>
            INSERT INTO user_manual_reward_records
                (user_id, reward_type, reward_note, source_announcement_id, recorded_by)
            VALUES
            <foreach collection="users" item="user" separator=",">
                (#{user}, #{type}, #{note,jdbcType=VARCHAR}, #{announcement}, #{admin})
            </foreach>
            </script>
            """)
    void register(@Param("users") List<Long> users, @Param("type") ManualRewardType type,
            @Param("note") String note, @Param("announcement") long announcement, @Param("admin") long admin);

    @Select("SELECT COUNT(*) FROM user_manual_reward_records WHERE user_id = #{user}")
    long count(long user);

    @Select("""
            <script>
            SELECT id, user_id, reward_type, source_announcement_id, recorded_at
            FROM user_manual_reward_records WHERE user_id IN
            <foreach collection="users" item="user" open="(" separator="," close=")">#{user}</foreach>
            </script>
            """)
    List<ManualRewardEntry.SummaryCandidate> summaryCandidates(@Param("users") List<Long> users);

    @Select("""
            SELECT id, reward_type, reward_note, source_announcement_id, recorded_at
            FROM user_manual_reward_records WHERE user_id = #{user}
            ORDER BY recorded_at DESC, id DESC LIMIT #{limit} OFFSET #{offset}
            """)
    List<ManualRewardEntry> page(@Param("user") long user,
            @Param("limit") int limit, @Param("offset") long offset);
}
