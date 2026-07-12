package com.ociworker.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ociworker.model.entity.TenantTrafficInstanceUsage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
@Mapper
public interface TenantTrafficInstanceUsageMapper extends BaseMapper<TenantTrafficInstanceUsage> {
    @Insert("""
            INSERT INTO oci_tenant_traffic_instance_usage
              (id, tenant_config_id, month_key, instance_id, instance_name, region, lifecycle_state, bytes_to_network, last_seen_time)
            VALUES
              (#{row.id}, #{row.tenantConfigId}, #{row.monthKey}, #{row.instanceId}, #{row.instanceName},
               #{row.region}, #{row.lifecycleState}, #{row.bytesToNetwork}, #{row.lastSeenTime})
            ON DUPLICATE KEY UPDATE
              instance_name = VALUES(instance_name), region = VALUES(region), lifecycle_state = VALUES(lifecycle_state),
              bytes_to_network = GREATEST(bytes_to_network, VALUES(bytes_to_network)), last_seen_time = VALUES(last_seen_time)
            """)
    int upsertMonthlyUsage(@Param("row") TenantTrafficInstanceUsage row);
}
