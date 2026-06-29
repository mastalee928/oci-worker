package com.ociworker.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ociworker.exception.OciException;
import com.ociworker.mapper.OciKvMapper;
import com.ociworker.model.entity.OciKv;
import com.ociworker.util.CommonUtils;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class OracleAiModelWhitelistService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TYPE = "oracle_ai_model_whitelist";

    @Resource
    private OciKvMapper kvMapper;

    public List<String> list(String ociUserId) {
        String tenantId = normalizeTenantId(ociUserId);
        if (tenantId == null) {
            return List.of();
        }
        OciKv kv = kvMapper.selectOne(new LambdaQueryWrapper<OciKv>()
                .eq(OciKv::getType, TYPE)
                .eq(OciKv::getCode, code(tenantId)));
        if (kv == null || kv.getValue() == null || kv.getValue().isBlank()) {
            return List.of();
        }
        try {
            List<String> raw = MAPPER.readValue(kv.getValue(), new TypeReference<List<String>>() {});
            return OracleAiPortBindingService.normalizeAllowedModels(raw);
        } catch (Exception e) {
            return List.of();
        }
    }

    public void save(String ociUserId, List<String> models) {
        String tenantId = normalizeTenantId(ociUserId);
        if (tenantId == null) {
            throw new OciException("租户不能为空");
        }
        List<String> normalized = OracleAiPortBindingService.normalizeAllowedModels(models);
        String value;
        try {
            value = MAPPER.writeValueAsString(normalized);
        } catch (Exception e) {
            throw new OciException("模型白名单保存失败");
        }
        String code = code(tenantId);
        OciKv kv = kvMapper.selectOne(new LambdaQueryWrapper<OciKv>()
                .eq(OciKv::getType, TYPE)
                .eq(OciKv::getCode, code));
        if (kv == null) {
            kv = new OciKv();
            kv.setId(CommonUtils.generateId());
            kv.setType(TYPE);
            kv.setCode(code);
            kv.setCreateTime(LocalDateTime.now());
            kv.setValue(value);
            kvMapper.insert(kv);
        } else {
            kv.setValue(value);
            kvMapper.updateById(kv);
        }
    }

    public String allowedModelsJson(String ociUserId) {
        List<String> models = list(ociUserId);
        if (models.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(models);
        } catch (Exception e) {
            return null;
        }
    }

    public int removeByTenantIds(List<String> tenantIds) {
        if (tenantIds == null || tenantIds.isEmpty()) {
            return 0;
        }
        Set<String> ids = new LinkedHashSet<>();
        for (String tenantId : tenantIds) {
            String normalized = normalizeTenantId(tenantId);
            if (normalized != null) {
                ids.add(normalized);
            }
        }
        if (ids.isEmpty()) {
            return 0;
        }
        List<String> codes = new ArrayList<>();
        for (String tenantId : ids) {
            codes.add(code(tenantId));
        }
        return kvMapper.delete(new LambdaQueryWrapper<OciKv>()
                .eq(OciKv::getType, TYPE)
                .in(OciKv::getCode, codes));
    }

    private static String code(String tenantId) {
        return "tenant:" + tenantId;
    }

    private static String normalizeTenantId(String ociUserId) {
        if (ociUserId == null || ociUserId.isBlank()) {
            return null;
        }
        String s = ociUserId.trim();
        return s.length() > 128 ? s.substring(0, 128) : s;
    }
}
