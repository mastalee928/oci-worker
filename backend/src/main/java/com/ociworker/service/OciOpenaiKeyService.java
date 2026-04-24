package com.ociworker.service;

import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ociworker.exception.OciException;
import com.ociworker.mapper.OciOpenaiKeyMapper;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.model.entity.OciOpenaiKey;
import com.ociworker.model.entity.OciUser;
import com.ociworker.util.CommonUtils;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class OciOpenaiKeyService {

    @Resource
    private OciOpenaiKeyMapper openaiKeyMapper;
    @Resource
    private OciUserMapper ociUserMapper;

    private static final String PREFIX = "sk-";
    private static final SecureRandom R = new SecureRandom();

    @Transactional(rollbackFor = Exception.class)
    public KeyCreateResult create(String ociUserId, String name) {
        if (ociUserId == null || ociUserId.isBlank()) {
            throw new OciException("请选择租户");
        }
        OciUser u = ociUserMapper.selectById(ociUserId);
        if (u == null) {
            throw new OciException("租户不存在");
        }
        int randomBytes = 32;
        byte[] buf = new byte[randomBytes];
        R.nextBytes(buf);
        StringBuilder sb = new StringBuilder(PREFIX);
        for (byte t : buf) {
            sb.append(String.format("%02x", t));
        }
        String keyPlain = sb.toString();
        String hash = DigestUtil.sha256Hex(keyPlain);
        OciOpenaiKey row = new OciOpenaiKey();
        row.setId(CommonUtils.generateId());
        row.setOciUserId(ociUserId);
        row.setKeyHash(hash);
        String prefix = keyPlain.length() > 16 ? keyPlain.substring(0, 12) : keyPlain;
        row.setKeyPrefix(prefix);
        row.setName(name);
        row.setDisabled(0);
        row.setCreateTime(LocalDateTime.now());
        openaiKeyMapper.insert(row);
        return new KeyCreateResult(row.getId(), keyPlain, prefix);
    }

    public List<OciOpenaiKey> listByTenant(String ociUserId) {
        if (ociUserId == null || ociUserId.isBlank()) {
            return List.of();
        }
        return openaiKeyMapper.selectList(
                new LambdaQueryWrapper<OciOpenaiKey>()
                        .eq(OciOpenaiKey::getOciUserId, ociUserId)
                        .orderByDesc(OciOpenaiKey::getCreateTime));
    }

    @Transactional(rollbackFor = Exception.class)
    public void setDisabled(String id, boolean disabled) {
        OciOpenaiKey k = openaiKeyMapper.selectById(id);
        if (k == null) {
            return;
        }
        k.setDisabled(disabled ? 1 : 0);
        openaiKeyMapper.updateById(k);
    }

    @Transactional(rollbackFor = Exception.class)
    public void remove(String id) {
        openaiKeyMapper.deleteById(id);
    }

    public OciOpenaiKey findByPlainKey(String plain) {
        if (plain == null || plain.isBlank() || !plain.startsWith("sk-")) {
            return null;
        }
        String hash = DigestUtil.sha256Hex(plain);
        return openaiKeyMapper.selectOne(
                new LambdaQueryWrapper<OciOpenaiKey>().eq(OciOpenaiKey::getKeyHash, hash));
    }

    public void updateLastUsed(String id) {
        if (id == null) {
            return;
        }
        openaiKeyMapper.update(
                null,
                new LambdaUpdateWrapper<OciOpenaiKey>()
                        .set(OciOpenaiKey::getLastUsed, LocalDateTime.now())
                        .eq(OciOpenaiKey::getId, id));
    }

    public record KeyCreateResult(String id, String plainKey, String keyPrefix) { }
}
