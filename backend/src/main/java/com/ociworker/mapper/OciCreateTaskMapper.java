package com.ociworker.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ociworker.model.entity.OciCreateTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface OciCreateTaskMapper extends BaseMapper<OciCreateTask> {
    @Select("""
            <script>
            SELECT user_id, COUNT(*) AS running_count
            FROM oci_create_task
            WHERE status = #{status}
              AND user_id IN
              <foreach collection="userIds" item="userId" open="(" separator="," close=")">
                #{userId}
              </foreach>
            GROUP BY user_id
            </script>
            """)
    List<Map<String, Object>> countByUserIdsAndStatus(
            @Param("userIds") List<String> userIds,
            @Param("status") String status);
}
