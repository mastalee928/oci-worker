package com.ociworker.service;

import com.oracle.bmc.monitoring.MonitoringClient;
import com.oracle.bmc.monitoring.model.*;
import com.oracle.bmc.monitoring.requests.SummarizeMetricsDataRequest;
import com.oracle.bmc.monitoring.responses.SummarizeMetricsDataResponse;
import com.ociworker.exception.OciException;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.model.dto.SysUserDTO;
import com.ociworker.model.entity.OciUser;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
public class TrafficService {

    @Resource
    private OciUserMapper userMapper;

    public Map<String, Object> getTrafficData(String userId, String instanceId, int minutes, String region) {
        OciUser ociUser = userMapper.selectById(userId);
        if (ociUser == null) throw new OciException("租户配置不存在");

        SysUserDTO dto = SysUserDTO.builder()
                .username(ociUser.getUsername())
                .ociCfg(SysUserDTO.OciCfg.builder()
                        .tenantId(ociUser.getOciTenantId())
                        .userId(ociUser.getOciUserId())
                        .fingerprint(ociUser.getOciFingerprint())
                        .region(ociUser.getOciRegion())
                        .privateKeyPath(ociUser.getOciKeyPath())
                        .build())
                .build();

        String r = (region == null || region.isBlank()) ? null : region.trim();
        try (OciClientService client = new OciClientService(dto, r)) {
            MonitoringClient monitoringClient = client.getMonitoringClient();
            Date endTime = new Date();
            Date startTime = new Date(endTime.getTime() - (long) minutes * 60 * 1000);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("inbound", queryMetric(monitoringClient, client.getCompartmentId(),
                    instanceId, "VnicFromNetworkBytes", startTime, endTime));
            result.put("outbound", queryMetric(monitoringClient, client.getCompartmentId(),
                    instanceId, "VnicToNetworkBytes", startTime, endTime));
            return result;
        } catch (Exception e) {
            throw new OciException("获取流量数据失败: " + e.getMessage());
        }
    }

    private List<Map<String, Object>> queryMetric(MonitoringClient monitoringClient,
                                                    String compartmentId, String instanceId,
                                                    String metricName, Date start, Date end) {
        String query = String.format("%s[1m]{resourceId = \"%s\"}.mean()", metricName, instanceId);

        SummarizeMetricsDataResponse response = monitoringClient.summarizeMetricsData(
                SummarizeMetricsDataRequest.builder()
                        .compartmentId(compartmentId)
                        .summarizeMetricsDataDetails(SummarizeMetricsDataDetails.builder()
                                .namespace("oci_computeagent")
                                .query(query)
                                .startTime(start)
                                .endTime(end)
                                .resolution("1m")
                                .build())
                        .build());

        List<Map<String, Object>> dataPoints = new ArrayList<>();
        for (MetricData metricData : response.getItems()) {
            for (AggregatedDatapoint dp : metricData.getAggregatedDatapoints()) {
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("timestamp", dp.getTimestamp().toString());
                point.put("value", dp.getValue());
                dataPoints.add(point);
            }
        }
        return dataPoints;
    }
}
