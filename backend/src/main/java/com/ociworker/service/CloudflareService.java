package com.ociworker.service;

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
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class CloudflareService {

    private static final String CF_API_BASE = "https://api.cloudflare.com/client/v4";

    @Resource
    private CfCfgMapper cfCfgMapper;
    @Lazy
    @Resource
    private OciProxyConfigService ociProxyConfigService;

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

        String body = httpSend(HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + cfg.getApiToken())
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .GET());

        JSONObject json = JSONUtil.parseObj(body);
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
        Map<String, Object> b = Map.of(
                "type", type, "name", name, "content", content,
                "proxied", proxied, "ttl", ttl);

        String body = httpSend(HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + cfg.getApiToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSONUtil.toJsonStr(b)))
                .timeout(Duration.ofSeconds(15)));

        JSONObject json = JSONUtil.parseObj(body);
        if (!json.getBool("success", false)) {
            throw new OciException("添加DNS记录失败: " + json.getStr("errors"));
        }
    }

    public void deleteDnsRecord(String cfgId, String recordId) {
        CfCfg cfg = cfCfgMapper.selectById(cfgId);
        if (cfg == null) throw new OciException("CF 配置不存在");

        String url = String.format("%s/zones/%s/dns_records/%s", CF_API_BASE, cfg.getZoneId(), recordId);
        httpSend(HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + cfg.getApiToken())
                .timeout(Duration.ofSeconds(15))
                .DELETE());
    }

    public void updateDnsRecord(String cfgId, String recordId, String type, String name,
                                 String content, boolean proxied, int ttl) {
        CfCfg cfg = cfCfgMapper.selectById(cfgId);
        if (cfg == null) throw new OciException("CF 配置不存在");

        String url = String.format("%s/zones/%s/dns_records/%s", CF_API_BASE, cfg.getZoneId(), recordId);
        Map<String, Object> b = Map.of(
                "type", type, "name", name, "content", content,
                "proxied", proxied, "ttl", ttl);

        httpSend(HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + cfg.getApiToken())
                .header("Content-Type", "application/json")
                .method("PUT", HttpRequest.BodyPublishers.ofString(JSONUtil.toJsonStr(b)))
                .timeout(Duration.ofSeconds(15)));
    }

    private String httpSend(HttpRequest.Builder b) {
        try {
            HttpClient c = ociProxyConfigService.newOutboundHttpClient();
            HttpRequest req = b.build();
            HttpResponse<String> r = c.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() < 200 || r.statusCode() >= 400) {
                throw new OciException("HTTP " + r.statusCode() + (r.body() != null && r.body().length() < 200
                        ? (": " + r.body()) : ""));
            }
            return r.body() == null ? "" : r.body();
        } catch (IOException e) {
            throw new OciException("请求失败: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OciException("请求中断");
        }
    }
}
