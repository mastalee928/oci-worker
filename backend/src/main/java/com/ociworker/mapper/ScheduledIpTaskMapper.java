package com.ociworker.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ociworker.model.entity.ScheduledIpTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.Date;

@Mapper
public interface ScheduledIpTaskMapper extends BaseMapper<ScheduledIpTask> {
    @Update("""
            UPDATE oci_scheduled_ip_task
            SET lock_owner = #{owner}, lock_until = #{lockUntil}, update_time = #{now}
            WHERE id = #{id}
              AND (lock_until IS NULL OR lock_until < #{now})
            """)
    int claimExecution(@Param("id") String id,
                       @Param("owner") String owner,
                       @Param("now") Date now,
                       @Param("lockUntil") Date lockUntil);

    @Update("""
            UPDATE oci_scheduled_ip_task
            SET lock_owner = NULL, lock_until = NULL, update_time = #{now}
            WHERE id = #{id} AND lock_owner = #{owner}
            """)
    int releaseExecution(@Param("id") String id,
                         @Param("owner") String owner,
                         @Param("now") Date now);
}
