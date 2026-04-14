package com.ociworker.service;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ociworker.exception.OciException;
import com.ociworker.mapper.CfCfgMapper;
import com.ociworker.model.entity.CfCfg;
import com.ociworker.util.CommonUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
public class CloudflareService {

    private static final String CF_API_BASE = "https://api.cloudflare.com/client/v4";

    @Resource
    private CfCfgMapper cfCfgMapper;

    public Page<CfCfg> listCfgPage(int current, int size) {
        return cfCfgMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<CfCfg>().orderByDesc(CfCfg::getCreateTime));
    }

    public void addCfg(CfCfg cfg) {
        cfg.setId(CommonUtils.generateId());
        cfg.setCreateTime(LocalDateTime.now());
        cfCfgMapper.insert(cfg);
    }

    public void removeCfg(String id) {
        cfCfgMapper.deleteById(id);
    }

    public List<Map<String, Object>> listDnsRecords(String cfgId, int page, int perPage) {
        CfCfg cfg = cfCfgMapper.selectById(cfgId);
        if (cfg == null) throw new OciException("CF 配置不存在");

        String url = String.format("%s/zones/%s/dns_records?page=%d&per_page=%d",
                CF_API_BASE, cfg.getZoneId(), page, perPage);

        HttpResponse resp = HttpRequest.get(url)
                .header("Authorization", "Bearer " + cfg.getApiToken())
                .header("Content-Type", "application/json")
                .timeout(15000)
                .execute();

        JSONObject json = JSONUtil.parseObj(resp.body());
        if (!json.getBool("success", false)) {
            throw new OciException("Cloudflare API 错误: " + json.getStr("errors"));
        }

        JSONArray result = json.getJSONArray("result");
        List<Map<String, Object>> records = new ArrayList<>();
        for (int i = 0; i < result.size(); i++) {
            JSONObject r = result.getJSONObject(i);
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", r.getStr("id"));
            map.put("type", r.getStr("type"));
            map.put("name", r.getStr("name"));
            map.put("content", r.getStr("content"));
            map.put("proxied", r.getBool("proxied"));
            map.put("ttl", r.getInt("ttl"));
            records.add(map);
        }
        return records;
    }

    public void addDnsRecord(String cfgId, String type, String name, String content, boolean proxied, int ttl) {
        CfCfg cfg = cfCfgMapper.selectById(cfgId);
        if (cfg == null) throw new OciException("CF 配置不存在");

        String url = String.format("%s/zones/%s/dns_records", CF_API_BASE, cfg.getZoneId());
        Map<String, Object> body = Map.of(
                "type", type, "name", name, "content", content,
                "proxied", proxied, "ttl", ttl);

        HttpResponse resp = HttpRequest.post(url)
                .header("Authorization", "Bearer " + cfg.getApiToken())
                .header("Content-Type", "application/json")
                .body(JSONUtil.toJsonStr(body))
                .timeout(15000)
                .execute();

        JSONObject json = JSONUtil.parseObj(resp.body());
        if (!json.getBool("success", false)) {
            throw new OciException("添加DNS记录失败: " + json.getStr("errors"));
        }
    }

    public void deleteDnsRecord(String cfgId, String recordId) {
        CfCfg cfg = cfCfgMapper.selectById(cfgId);
        if (cfg == null) throw new OciException("CF 配置不存在");

        String url = String.format("%s/zones/%s/dns_records/%s", CF_API_BASE, cfg.getZoneId(), recordId);
        HttpRequest.delete(url)
                .header("Authorization", "Bearer " + cfg.getApiToken())
                .timeout(15000)
                .execute();
    }

    public void updateDnsRecord(String cfgId, String recordId, String type, String name,
                                 String content, boolean proxied, int ttl) {
        CfCfg cfg = cfCfgMapper.selectById(cfgId);
        if (cfg == null) throw new OciException("CF 配置不存在");

        String url = String.format("%s/zones/%s/dns_records/%s", CF_API_BASE, cfg.getZoneId(), recordId);
        Map<String, Object> body = Map.of(
                "type", type, "name", name, "content", content,
                "proxied", proxied, "ttl", ttl);

        HttpRequest.put(url)
                .header("Authorization", "Bearer " + cfg.getApiToken())
                .header("Content-Type", "application/json")
                .body(JSONUtil.toJsonStr(body))
                .timeout(15000)
                .execute();
    }
}
