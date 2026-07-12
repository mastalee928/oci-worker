package com.ociworker.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ociworker.model.entity.TenantTrafficProtection;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.Date;

@Mapper
public interface TenantTrafficProtectionMapper extends BaseMapper<TenantTrafficProtection> {
    @Update("""
            UPDATE oci_tenant_traffic_protection
            SET stop_executed = 1, stop_executed_time = #{executedAt}, update_time = #{executedAt}
            WHERE tenant_config_id = #{tenantConfigId} AND stop_executed = 0
            """)
    int claimStopExecution(@Param("tenantConfigId") String tenantConfigId,
                           @Param("executedAt") Date executedAt);

    @Update("""
            UPDATE oci_tenant_traffic_protection
            SET collection_lock_owner = #{owner}, collection_lock_until = #{lockUntil}
            WHERE tenant_config_id = #{tenantConfigId}
              AND (collection_lock_until IS NULL OR collection_lock_until < #{now} OR collection_lock_owner = #{owner})
            """)
    int claimCollection(@Param("tenantConfigId") String tenantConfigId,
                        @Param("owner") String owner,
                        @Param("now") Date now,
                        @Param("lockUntil") Date lockUntil);

    @Update("""
            UPDATE oci_tenant_traffic_protection
            SET collection_lock_until = #{lockUntil}
            WHERE tenant_config_id = #{tenantConfigId} AND collection_lock_owner = #{owner}
            """)
    int extendCollection(@Param("tenantConfigId") String tenantConfigId,
                         @Param("owner") String owner,
                         @Param("lockUntil") Date lockUntil);

    @Update("""
            UPDATE oci_tenant_traffic_protection
            SET collection_lock_owner = NULL, collection_lock_until = NULL
            WHERE tenant_config_id = #{tenantConfigId} AND collection_lock_owner = #{owner}
            """)
    int releaseCollection(@Param("tenantConfigId") String tenantConfigId,
                          @Param("owner") String owner);
}
