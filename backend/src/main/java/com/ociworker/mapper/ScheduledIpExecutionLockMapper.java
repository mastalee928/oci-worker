package com.ociworker.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.Date;

@Mapper
public interface ScheduledIpExecutionLockMapper {

    @Insert("""
            INSERT IGNORE INTO oci_scheduled_ip_instance_lock
                (instance_key, update_time)
            VALUES
                (#{instanceKey}, #{now})
            """)
    int ensureRow(@Param("instanceKey") String instanceKey,
                  @Param("now") Date now);

    @Update("""
            UPDATE oci_scheduled_ip_instance_lock lock_row
            LEFT JOIN oci_scheduled_ip_task task_row
              ON task_row.id = lock_row.enabled_task_id
            SET lock_row.enabled_task_id = NULL,
                lock_row.update_time = #{now}
            WHERE lock_row.instance_key = #{instanceKey}
              AND lock_row.enabled_task_id IS NOT NULL
              AND (task_row.id IS NULL OR task_row.enabled = 0)
            """)
    int clearStaleEnabledTask(@Param("instanceKey") String instanceKey,
                              @Param("now") Date now);

    @Update("""
            UPDATE oci_scheduled_ip_instance_lock
            SET enabled_task_id = #{taskId}, update_time = #{now}
            WHERE instance_key = #{instanceKey}
              AND (enabled_task_id IS NULL OR enabled_task_id = #{taskId})
            """)
    int claimEnabledTask(@Param("instanceKey") String instanceKey,
                         @Param("taskId") String taskId,
                         @Param("now") Date now);

    @Update("""
            UPDATE oci_scheduled_ip_instance_lock
            SET enabled_task_id = NULL, update_time = #{now}
            WHERE instance_key = #{instanceKey}
              AND enabled_task_id = #{taskId}
            """)
    int releaseEnabledTask(@Param("instanceKey") String instanceKey,
                           @Param("taskId") String taskId,
                           @Param("now") Date now);

    @Update("""
            UPDATE oci_scheduled_ip_instance_lock
            SET lock_owner = #{owner}, lock_until = #{lockUntil}, update_time = #{now}
            WHERE instance_key = #{instanceKey}
              AND (lock_until IS NULL OR lock_until < #{now})
            """)
    int claimExecution(@Param("instanceKey") String instanceKey,
                       @Param("owner") String owner,
                       @Param("now") Date now,
                       @Param("lockUntil") Date lockUntil);

    @Update("""
            UPDATE oci_scheduled_ip_instance_lock
            SET lock_owner = NULL, lock_until = NULL, update_time = #{now}
            WHERE instance_key = #{instanceKey}
              AND lock_owner = #{owner}
            """)
    int releaseExecution(@Param("instanceKey") String instanceKey,
                         @Param("owner") String owner,
                         @Param("now") Date now);
}
