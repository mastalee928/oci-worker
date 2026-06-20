package com.ociworker.service;

import com.oracle.bmc.core.model.Instance;
import com.oracle.bmc.core.model.Subnet;
import com.oracle.bmc.core.model.Vnic;
import com.oracle.bmc.core.model.VnicAttachment;
import com.oracle.bmc.core.requests.GetInstanceRequest;
import com.oracle.bmc.core.requests.GetSubnetRequest;
import com.oracle.bmc.core.requests.GetVnicRequest;
import com.oracle.bmc.core.requests.ListVnicAttachmentsRequest;
import com.oracle.bmc.monitoring.MonitoringClient;
import com.oracle.bmc.monitoring.model.AggregatedDatapoint;
import com.oracle.bmc.monitoring.model.MetricData;
import com.oracle.bmc.monitoring.model.SummarizeMetricsDataDetails;
import com.oracle.bmc.monitoring.requests.SummarizeMetricsDataRequest;
import com.oracle.bmc.monitoring.responses.SummarizeMetricsDataResponse;
import com.ociworker.exception.OciException;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.model.dto.SysUserDTO;
import com.ociworker.model.entity.OciUser;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Slf4j
@Service
public class TrafficService {

    @Resource
    private OciUserMapper userMapper;

    public Map<String, Object> getTrafficData(String userId, String instanceId, int minutes, String region,
                                              String startTimeIso, String endTimeIso) {
        if (instanceId == null || instanceId.isBlank()) {
            throw new OciException("实例 OCID 不能为空");
        }
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
        TimeRange timeRange = resolveTimeRange(minutes, startTimeIso, endTimeIso);
        int windowMinutes = timeRange.minutes;
        String interval = chooseInterval(windowMinutes);
        try (OciClientService client = new OciClientService(dto, r)) {
            MonitoringClient monitoringClient = client.getMonitoringClient();
            Date startTime = timeRange.startTime;
            Date endTime = timeRange.endTime;

            List<VnicTarget> targets = listInstanceVnics(client, instanceId);
            Map<String, double[]> summaryPointMap = new TreeMap<>();
            List<Map<String, Object>> vnicRows = new ArrayList<>();

            for (VnicTarget target : targets) {
                List<MetricPoint> inboundPoints = queryMetric(monitoringClient, target.metricCompartmentId,
                        target.vnicId, "VnicFromNetworkBytes", interval, startTime, endTime);
                List<MetricPoint> outboundPoints = queryMetric(monitoringClient, target.metricCompartmentId,
                        target.vnicId, "VnicToNetworkBytes", interval, startTime, endTime);

                Map<String, double[]> vnicPointMap = new TreeMap<>();
                double inboundTotal = mergeMetricPoints(vnicPointMap, inboundPoints, 0);
                double outboundTotal = mergeMetricPoints(vnicPointMap, outboundPoints, 1);
                mergeMetricPoints(summaryPointMap, inboundPoints, 0);
                mergeMetricPoints(summaryPointMap, outboundPoints, 1);

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("vnicId", target.vnicId);
                row.put("displayName", target.displayName);
                row.put("privateIp", target.privateIp);
                row.put("publicIp", target.publicIp);
                row.put("subnetId", target.subnetId);
                row.put("metricCompartmentId", target.metricCompartmentId);
                row.put("inbound", Math.round(inboundTotal));
                row.put("outbound", Math.round(outboundTotal));
                row.put("total", Math.round(inboundTotal + outboundTotal));
                row.put("points", toPointList(vnicPointMap));
                vnicRows.add(row);
            }

            double inbound = sumColumn(summaryPointMap, 0);
            double outbound = sumColumn(summaryPointMap, 1);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("instanceId", instanceId);
            result.put("minutes", windowMinutes);
            result.put("interval", interval);
            result.put("customRange", timeRange.customRange);
            result.put("startTime", startTime.toInstant().toString());
            result.put("endTime", endTime.toInstant().toString());
            result.put("inbound", Math.round(inbound));
            result.put("outbound", Math.round(outbound));
            result.put("total", Math.round(inbound + outbound));
            result.put("vnicCount", vnicRows.size());
            result.put("points", toPointList(summaryPointMap));
            result.put("vnics", vnicRows);
            return result;
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException("获取流量数据失败: " + e.getMessage());
        }
    }

    private int normalizeWindow(int minutes) {
        if (minutes <= 60) return 60;
        if (minutes <= 1440) return 1440;
        if (minutes <= 10080) return 10080;
        return 43200;
    }

    private TimeRange resolveTimeRange(int minutes, String startTimeIso, String endTimeIso) {
        boolean hasStart = startTimeIso != null && !startTimeIso.isBlank();
        boolean hasEnd = endTimeIso != null && !endTimeIso.isBlank();
        if (hasStart != hasEnd) {
            throw new OciException("日期范围格式无效");
        }
        boolean customRange = hasStart && hasEnd;
        if (!customRange) {
            int windowMinutes = normalizeWindow(minutes);
            Date endTime = new Date();
            Date startTime = new Date(endTime.getTime() - (long) windowMinutes * 60 * 1000);
            return new TimeRange(startTime, endTime, windowMinutes, false);
        }

        try {
            Instant start = Instant.parse(startTimeIso);
            Instant end = Instant.parse(endTimeIso);
            if (!start.isBefore(end)) {
                throw new OciException("开始时间必须早于结束时间");
            }
            long rangeMillis = end.toEpochMilli() - start.toEpochMilli();
            long maxMillis = 90L * 24 * 60 * 60 * 1000;
            if (rangeMillis > maxMillis) {
                throw new OciException("流量自定义查询最多支持 90 天");
            }
            int windowMinutes = (int) Math.max(1, Math.ceil(rangeMillis / 60000.0));
            return new TimeRange(Date.from(start), Date.from(end), windowMinutes, true);
        } catch (OciException e) {
            throw e;
        } catch (Exception e) {
            throw new OciException("日期范围格式无效");
        }
    }

    private String chooseInterval(int minutes) {
        if (minutes <= 60) return "1m";
        if (minutes <= 1440) return "5m";
        if (minutes <= 10080) return "1h";
        return "1d";
    }

    private List<VnicTarget> listInstanceVnics(OciClientService client, String instanceId) {
        String instanceCompartmentId = client.getCompartmentId();
        try {
            Instance instance = client.getComputeClient().getInstance(
                    GetInstanceRequest.builder().instanceId(instanceId).build()
            ).getInstance();
            if (instance != null && instance.getCompartmentId() != null && !instance.getCompartmentId().isBlank()) {
                instanceCompartmentId = instance.getCompartmentId();
            }
        } catch (Exception e) {
            log.warn("获取实例所属区间失败，将使用默认区间查询 VNIC: {}", e.getMessage());
        }

        List<VnicAttachment> attachments = new ArrayList<>();
        String page = null;
        do {
            var response = client.getComputeClient().listVnicAttachments(
                    ListVnicAttachmentsRequest.builder()
                            .compartmentId(instanceCompartmentId)
                            .instanceId(instanceId)
                            .page(page)
                            .build()
            );
            if (response.getItems() != null) {
                attachments.addAll(response.getItems());
            }
            page = response.getOpcNextPage();
        } while (page != null && !page.isBlank());
        if (attachments == null || attachments.isEmpty()) {
            throw new OciException("未找到实例的 VNIC");
        }

        List<VnicTarget> targets = new ArrayList<>();
        for (VnicAttachment attachment : attachments) {
            String vnicId = attachment.getVnicId();
            if (vnicId == null || vnicId.isBlank()) continue;
            String state = attachment.getLifecycleState() == null ? "" : String.valueOf(attachment.getLifecycleState());
            if (!state.isBlank() && !"ATTACHED".equalsIgnoreCase(state)) continue;

            VnicTarget target = new VnicTarget();
            target.vnicId = vnicId;
            target.subnetId = attachment.getSubnetId();
            target.displayName = attachment.getDisplayName();
            target.metricCompartmentId = client.getCompartmentId();
            try {
                Vnic vnic = client.getVirtualNetworkClient().getVnic(
                        GetVnicRequest.builder().vnicId(vnicId).build()
                ).getVnic();
                if (vnic != null) {
                    target.displayName = firstNotBlank(vnic.getDisplayName(), target.displayName, shortId(vnicId));
                    target.privateIp = vnic.getPrivateIp();
                    target.publicIp = vnic.getPublicIp();
                    target.subnetId = firstNotBlank(vnic.getSubnetId(), target.subnetId);
                }
            } catch (Exception e) {
                log.warn("获取 VNIC 详情失败: {}", e.getMessage());
            }

            if (target.subnetId != null && !target.subnetId.isBlank()) {
                try {
                    Subnet subnet = client.getVirtualNetworkClient().getSubnet(
                            GetSubnetRequest.builder().subnetId(target.subnetId).build()
                    ).getSubnet();
                    if (subnet != null && subnet.getCompartmentId() != null && !subnet.getCompartmentId().isBlank()) {
                        target.metricCompartmentId = subnet.getCompartmentId();
                    }
                } catch (Exception e) {
                    log.warn("获取 VNIC 所属子网详情失败: {}", e.getMessage());
                }
            }

            if (target.displayName == null || target.displayName.isBlank()) {
                target.displayName = shortId(vnicId);
            }
            targets.add(target);
        }
        if (targets.isEmpty()) {
            throw new OciException("未找到已附加的 VNIC");
        }
        return targets;
    }

    private List<MetricPoint> queryMetric(MonitoringClient monitoringClient,
                                          String compartmentId,
                                          String vnicId,
                                          String metricName,
                                          String interval,
                                          Date start,
                                          Date end) {
        String query = String.format("%s[%s]{resourceId = \"%s\"}.sum()", metricName, interval, vnicId);

        SummarizeMetricsDataResponse response = monitoringClient.summarizeMetricsData(
                SummarizeMetricsDataRequest.builder()
                        .compartmentId(compartmentId)
                        .summarizeMetricsDataDetails(SummarizeMetricsDataDetails.builder()
                                .namespace("oci_vcn")
                                .query(query)
                                .startTime(start)
                                .endTime(end)
                                .resolution(interval)
                                .build())
                        .build());

        List<MetricPoint> dataPoints = new ArrayList<>();
        if (response.getItems() == null) return dataPoints;
        for (MetricData metricData : response.getItems()) {
            if (metricData.getAggregatedDatapoints() == null) continue;
            for (AggregatedDatapoint dp : metricData.getAggregatedDatapoints()) {
                if (dp.getTimestamp() == null || dp.getValue() == null) continue;
                dataPoints.add(new MetricPoint(dp.getTimestamp().toInstant().toString(), dp.getValue()));
            }
        }
        return dataPoints;
    }

    private double mergeMetricPoints(Map<String, double[]> pointMap, List<MetricPoint> points, int column) {
        double total = 0;
        for (MetricPoint point : points) {
            double[] values = pointMap.computeIfAbsent(point.timestamp, k -> new double[]{0, 0});
            values[column] += point.value;
            total += point.value;
        }
        return total;
    }

    private double sumColumn(Map<String, double[]> pointMap, int column) {
        double total = 0;
        for (double[] values : pointMap.values()) {
            total += values[column];
        }
        return total;
    }

    private List<Map<String, Object>> toPointList(Map<String, double[]> pointMap) {
        List<Map<String, Object>> points = new ArrayList<>();
        for (Map.Entry<String, double[]> entry : pointMap.entrySet()) {
            double inbound = entry.getValue()[0];
            double outbound = entry.getValue()[1];
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("timestamp", entry.getKey());
            point.put("inbound", Math.round(inbound));
            point.put("outbound", Math.round(outbound));
            point.put("total", Math.round(inbound + outbound));
            points.add(point);
        }
        return points;
    }

    private String firstNotBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    private String shortId(String id) {
        if (id == null || id.length() <= 12) return id == null ? "" : id;
        return id.substring(0, 8) + "..." + id.substring(id.length() - 4);
    }

    private static class VnicTarget {
        String vnicId;
        String displayName;
        String privateIp;
        String publicIp;
        String subnetId;
        String metricCompartmentId;
    }

    private record MetricPoint(String timestamp, double value) {
    }

    private record TimeRange(Date startTime, Date endTime, int minutes, boolean customRange) {
    }
}
