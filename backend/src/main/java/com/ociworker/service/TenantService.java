package com.ociworker.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ociworker.enums.TaskStatusEnum;
import com.ociworker.exception.OciException;
import com.ociworker.mapper.OciCreateTaskMapper;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.model.entity.OciCreateTask;
import com.ociworker.model.entity.OciUser;
import com.ociworker.model.params.IdListParams;
import com.ociworker.model.params.PageParams;
import com.ociworker.model.params.TenantParams;
import com.ociworker.util.CommonUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
public class TenantService {

    @Resource
    private OciUserMapper userMapper;
    @Resource
    private OciCreateTaskMapper taskMapper;

    @Value("${oci-cfg.key-dir-path}")
    private String keyDirPath;

    public Page<Map<String, Object>> list(PageParams params) {
        Page<OciUser> page = new Page<>(params.getCurrent(), params.getSize());
        LambdaQueryWrapper<OciUser> wrapper = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(params.getKeyword())) {
            wrapper.and(w -> w
                    .like(OciUser::getUsername, params.getKeyword())
                    .or().like(OciUser::getTenantName, params.getKeyword())
                    .or().like(OciUser::getOciRegion, params.getKeyword()));
        }
        wrapper.orderByDesc(OciUser::getCreateTime);
        Page<OciUser> result = userMapper.selectPage(page, wrapper);

        Page<Map<String, Object>> enriched = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        enriched.setRecords(result.getRecords().stream().map(u -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", u.getId());
            map.put("username", u.getUsername());
            map.put("tenantName", u.getTenantName());
            map.put("ociTenantId", u.getOciTenantId());
            map.put("ociUserId", u.getOciUserId());
            map.put("ociFingerprint", u.getOciFingerprint());
            map.put("ociRegion", u.getOciRegion());
            map.put("ociKeyPath", u.getOciKeyPath());
            map.put("planType", u.getPlanType());
            map.put("createTime", u.getCreateTime());

            long running = taskMapper.selectCount(
                    new LambdaQueryWrapper<OciCreateTask>()
                            .eq(OciCreateTask::getUserId, u.getId())
                            .eq(OciCreateTask::getStatus, TaskStatusEnum.RUNNING.getStatus()));
            map.put("taskStatus", running > 0 ? "执行开机任务中" : "无开机任务");
            map.put("hasRunningTask", running > 0);
            return map;
        }).toList());
        return enriched;
    }

    public void add(TenantParams params) {
        long duplicateCount = userMapper.selectCount(
                new LambdaQueryWrapper<OciUser>()
                        .eq(OciUser::getOciTenantId, params.getOciTenantId())
                        .eq(OciUser::getOciUserId, params.getOciUserId())
                        .eq(OciUser::getOciRegion, params.getOciRegion()));
        if (duplicateCount > 0) {
            throw new OciException("该租户配置已存在（相同 Tenant ID + User ID + Region），请勿重复添加");
        }

        long nameCount = userMapper.selectCount(
                new LambdaQueryWrapper<OciUser>().eq(OciUser::getUsername, params.getUsername()));
        if (nameCount > 0) {
            throw new OciException("名称「" + params.getUsername() + "」已被使用，请更换名称");
        }

        OciUser user = new OciUser();
        user.setId(CommonUtils.generateId());
        user.setUsername(params.getUsername());
        user.setOciTenantId(params.getOciTenantId());
        user.setOciUserId(params.getOciUserId());
        user.setOciFingerprint(params.getOciFingerprint());
        user.setOciRegion(params.getOciRegion());
        user.setOciKeyPath(params.getOciKeyPath());
        user.setCreateTime(LocalDateTime.now());
        userMapper.insert(user);
        log.info("Added tenant config: {}", params.getUsername());

        Thread.ofVirtual().start(() -> fetchTenantInfo(user));
    }

    public void update(TenantParams params) {
        if (StrUtil.isBlank(params.getId())) {
            throw new OciException("ID不能为空");
        }
        OciUser user = userMapper.selectById(params.getId());
        if (user == null) {
            throw new OciException("配置不存在");
        }
        user.setUsername(params.getUsername());
        user.setOciTenantId(params.getOciTenantId());
        user.setOciUserId(params.getOciUserId());
        user.setOciFingerprint(params.getOciFingerprint());
        user.setOciRegion(params.getOciRegion());
        if (StrUtil.isNotBlank(params.getOciKeyPath())) {
            user.setOciKeyPath(params.getOciKeyPath());
        }
        userMapper.updateById(user);
        log.info("Updated tenant config: {}", params.getUsername());
    }

    public void remove(IdListParams params) {
        userMapper.deleteBatchIds(params.getIdList());
        log.info("Removed tenant configs: {}", params.getIdList());
    }

    public OciUser getById(String id) {
        OciUser user = userMapper.selectById(id);
        if (user == null) {
            throw new OciException("配置不存在");
        }
        return user;
    }

    public void refreshPlanType(String id) {
        OciUser user = userMapper.selectById(id);
        if (user == null) throw new OciException("配置不存在");
        fetchTenantInfo(user);
    }

    private void fetchTenantInfo(OciUser user) {
        try {
            com.ociworker.model.dto.SysUserDTO dto = com.ociworker.model.dto.SysUserDTO.builder()
                    .username(user.getUsername())
                    .ociCfg(com.ociworker.model.dto.SysUserDTO.OciCfg.builder()
                            .tenantId(user.getOciTenantId())
                            .userId(user.getOciUserId())
                            .fingerprint(user.getOciFingerprint())
                            .region(user.getOciRegion())
                            .privateKeyPath(user.getOciKeyPath())
                            .build())
                    .build();
            try (OciClientService client = new OciClientService(dto)) {
                // Fetch tenant name
                try {
                    var tenancy = client.getIdentityClient().getTenancy(
                            com.oracle.bmc.identity.requests.GetTenancyRequest.builder()
                                    .tenancyId(user.getOciTenantId())
                                    .build()).getTenancy();
                    if (tenancy != null && StrUtil.isNotBlank(tenancy.getName())) {
                        user.setTenantName(tenancy.getName());
                    }
                } catch (Exception e) {
                    log.warn("Failed to fetch tenantName for {}: {}", user.getUsername(), e.getMessage());
                }

                // Fetch plan type
                com.oracle.bmc.ospgateway.SubscriptionServiceClient ospClient =
                        com.oracle.bmc.ospgateway.SubscriptionServiceClient.builder().build(client.getProvider());
                try {
                    var resp = ospClient.listSubscriptions(
                            com.oracle.bmc.ospgateway.requests.ListSubscriptionsRequest.builder()
                                    .ospHomeRegion(user.getOciRegion())
                                    .compartmentId(client.getCompartmentId())
                                    .build());
                    var items = resp.getSubscriptionCollection().getItems();
                    if (items != null && !items.isEmpty()) {
                        String planType = items.get(0).getPlanType() != null
                                ? items.get(0).getPlanType().getValue() : "UNKNOWN";
                        user.setPlanType(planType);
                    }
                } finally {
                    ospClient.close();
                }

                userMapper.updateById(user);
            }
        } catch (Exception e) {
            log.warn("Failed to fetch tenant info for {}: {}", user.getUsername(), e.getMessage());
        }
    }

    public String uploadKey(MultipartFile file) throws IOException {
        Path dirPath = Path.of(System.getProperty("user.dir"), keyDirPath).normalize();
        File dir = dirPath.toFile();
        if (!dir.exists()) {
            dir.mkdirs();
        }
        String fileName = CommonUtils.generateId() + ".pem";
        File target = new File(dir, fileName);
        file.transferTo(target);
        log.info("Uploaded key file: {}", target.getAbsolutePath());
        return target.getAbsolutePath();
    }
}
