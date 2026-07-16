package com.ociworker.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ociworker.model.entity.ScheduledIpRunLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

import java.util.Date;

@Mapper
public interface ScheduledIpRunLogMapper extends BaseMapper<ScheduledIpRunLog> {
    @Delete("DELETE FROM oci_scheduled_ip_run_log WHERE started_at < #{cutoff}")
    int deleteOlderThan(@Param("cutoff") Date cutoff);
}
