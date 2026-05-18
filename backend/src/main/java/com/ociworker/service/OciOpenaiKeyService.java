package com.ociworker.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ociworker.exception.OciException;
import com.ociworker.mapper.OciOpenaiKeyMapper;
import com.ociworker.mapper.OciUserMapper;
import com.ociworker.model.entity.OciOpenaiKey;
import com.ociworker.model.entity.OciUser;
import com.ociworker.util.CommonUtils;
import com.ociworker.util.OciOpenaiKeyCipher;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${web.password}")
    private String webPassword;

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
        row.setKeyEncrypted(OciOpenaiKeyCipher.encrypt(keyPlain, webPassword));
        row.setName(name);
        row.setDisabled(0);
        row.setCreateTime(LocalDateTime.now());
        openaiKeyMapper.insert(row);
        return new KeyCreateResult(row.getId(), keyPlain, prefix, OciOpenaiKeyCipher.maskForDisplay(keyPlain));
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

    /** 列表用脱敏展示，不含完整密钥 */
    public String maskForList(OciOpenaiKey k) {
        if (k == null) {
            return "sk-****";
        }
        if (StrUtil.isNotBlank(k.getKeyEncrypted())) {
            try {
                String plain = OciOpenaiKeyCipher.decrypt(k.getKeyEncrypted(), webPassword);
                if (StrUtil.isNotBlank(plain)) {
                    return OciOpenaiKeyCipher.maskForDisplay(plain);
                }
            } catch (Exception ignored) {
            }
        }
        if (StrUtil.isNotBlank(k.getKeyPrefix())) {
            return k.getKeyPrefix() + "****";
        }
        return "sk-****";
    }

    public String revealPlainKey(String id) {
        if (id == null || id.isBlank()) {
            throw new OciException("id 必填");
        }
        OciOpenaiKey k = openaiKeyMapper.selectById(id);
        if (k == null) {
            throw new OciException("密钥不存在");
        }
        if (StrUtil.isBlank(k.getKeyEncrypted())) {
            throw new OciException("该密钥为旧数据，未保存完整密钥，请删除后重新生成");
        }
        String plain = OciOpenaiKeyCipher.decrypt(k.getKeyEncrypted(), webPassword);
        if (StrUtil.isBlank(plain)) {
            throw new OciException("密钥解密失败（可能修改过面板登录密码），请重新生成密钥");
        }
        return plain;
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

    public record KeyCreateResult(String id, String plainKey, String keyPrefix, String keyMasked) {}
}
