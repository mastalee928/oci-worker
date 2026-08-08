package com.ociworker.service;

import cn.hutool.core.util.StrUtil;
import com.ociworker.exception.OciException;
import com.oracle.bmc.identity.model.ApiKey;
import com.oracle.bmc.identity.model.CreateApiKeyDetails;
import com.oracle.bmc.identity.requests.DeleteApiKeyRequest;
import com.oracle.bmc.identity.requests.ListApiKeysRequest;
import com.oracle.bmc.identity.requests.UploadApiKeyRequest;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class UserApiKeyService {

    private static final long SESSION_EXPIRE_MS = 10 * 60 * 1000L;
    private static final int RSA_KEY_SIZE = 2048;

    private final Map<String, ApiKeySession> sessions = new ConcurrentHashMap<>();
    private final SecureRandom secureRandom = new SecureRandom();

    @Resource
    private DomainManagementService domainManagementService;

    private record ApiKeySession(String tenantId, String userId, long expireAt) {}

    public void assertManageableUser(String tenantId, String userId) {
        if (StrUtil.isBlank(tenantId)) {
            throw new OciException("租户 ID 不能为空");
        }
        if (StrUtil.isBlank(userId) || !userId.trim().startsWith("ocid1.user.")) {
            throw new OciException("该用户未返回 OCI 用户 OCID，无法管理 API Key");
        }
    }

    public String openSession(String tenantId, String userId) {
        String normalizedTenantId = StrUtil.trim(tenantId);
        String normalizedUserId = StrUtil.trim(userId);
        assertManageableUser(normalizedTenantId, normalizedUserId);
        removeExpiredSessions();

        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        sessions.put(token, new ApiKeySession(
                normalizedTenantId,
                normalizedUserId,
                System.currentTimeMillis() + SESSION_EXPIRE_MS));
        return token;
    }

    public void closeSession(String sessionToken) {
        String token = StrUtil.trimToNull(sessionToken);
        if (token != null) {
            sessions.remove(token);
        }
    }

    public List<Map<String, Object>> listApiKeys(
            String sessionToken,
            String tenantId,
            String userId) {
        ApiKeySession session = requireSession(sessionToken, tenantId, userId);
        try (OciClientService oci = domainManagementService.openOciClient(session.tenantId())) {
            List<ApiKey> items = oci.getIdentityClient().listApiKeys(
                    ListApiKeysRequest.builder()
                            .userId(session.userId())
                            .build())
                    .getItems();
            if (items == null || items.isEmpty()) {
                return List.of();
            }
            List<Map<String, Object>> result = new ArrayList<>(items.size());
            for (ApiKey item : items) {
                result.add(toApiKeyMap(item));
            }
            return result;
        }
    }

    public Map<String, Object> createApiKey(
            String sessionToken,
            String tenantId,
            String userId) {
        ApiKeySession session = requireSession(sessionToken, tenantId, userId);
        KeyPair keyPair = generateKeyPair();
        String publicKeyPem = toPem("PUBLIC KEY", keyPair.getPublic().getEncoded());
        String privateKeyPem = toPem("PRIVATE KEY", keyPair.getPrivate().getEncoded());

        try (OciClientService oci = domainManagementService.openOciClient(session.tenantId())) {
            ApiKey created = oci.getIdentityClient().uploadApiKey(
                    UploadApiKeyRequest.builder()
                            .userId(session.userId())
                            .createApiKeyDetails(CreateApiKeyDetails.builder()
                                    .key(publicKeyPem)
                                    .build())
                            .build())
                    .getApiKey();
            if (created == null) {
                throw new OciException("OCI 未返回新增的 API Key");
            }

            Map<String, Object> result = toApiKeyMap(created);
            result.put("publicKeyPem", publicKeyPem);
            result.put("privateKeyPem", privateKeyPem);
            result.put("config", buildOciConfig(oci, session.userId(), created.getFingerprint()));
            return result;
        }
    }

    public void deleteApiKey(
            String sessionToken,
            String tenantId,
            String userId,
            String fingerprint) {
        ApiKeySession session = requireSession(sessionToken, tenantId, userId);
        String normalizedFingerprint = StrUtil.trimToNull(fingerprint);
        if (normalizedFingerprint == null) {
            throw new OciException("API Key Fingerprint 不能为空");
        }
        try (OciClientService oci = domainManagementService.openOciClient(session.tenantId())) {
            String mountedFingerprint = oci.getUser() == null || oci.getUser().getOciCfg() == null
                    ? null
                    : StrUtil.trimToNull(oci.getUser().getOciCfg().getFingerprint());
            if (mountedFingerprint != null
                    && mountedFingerprint.equalsIgnoreCase(normalizedFingerprint)) {
                throw new OciException("该 API 目前挂载在 OCIWorker，删除会影响操作");
            }
            oci.getIdentityClient().deleteApiKey(
                    DeleteApiKeyRequest.builder()
                            .userId(session.userId())
                            .fingerprint(normalizedFingerprint)
                            .build());
        }
    }

    private ApiKeySession requireSession(String sessionToken, String tenantId, String userId) {
        String token = StrUtil.trimToNull(sessionToken);
        String normalizedTenantId = StrUtil.trimToNull(tenantId);
        String normalizedUserId = StrUtil.trimToNull(userId);
        if (token == null || normalizedTenantId == null || normalizedUserId == null) {
            throw new OciException("API Key 管理会话无效，请重新进行 Telegram 验证");
        }

        ApiKeySession session = sessions.get(token);
        if (session == null) {
            throw new OciException("API Key 管理会话不存在，请重新进行 Telegram 验证");
        }
        if (System.currentTimeMillis() > session.expireAt()) {
            sessions.remove(token);
            throw new OciException("API Key 管理会话已过期，请重新进行 Telegram 验证");
        }
        if (!session.tenantId().equals(normalizedTenantId)
                || !session.userId().equals(normalizedUserId)) {
            throw new OciException("API Key 管理会话与当前用户不匹配");
        }
        return session;
    }

    private void removeExpiredSessions() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(entry -> now > entry.getValue().expireAt());
    }

    private KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(RSA_KEY_SIZE, secureRandom);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new OciException("生成 API Key 密钥对失败: " + e.getMessage());
        }
    }

    private static String toPem(String type, byte[] encoded) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(encoded);
        return "-----BEGIN " + type + "-----\n"
                + body
                + "\n-----END " + type + "-----\n";
    }

    private static Map<String, Object> toApiKeyMap(ApiKey apiKey) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("keyId", apiKey.getKeyId());
        result.put("keyValue", apiKey.getKeyValue());
        result.put("fingerprint", apiKey.getFingerprint());
        result.put("userId", apiKey.getUserId());
        result.put("timeCreated", apiKey.getTimeCreated() == null
                ? null
                : apiKey.getTimeCreated().toInstant().toString());
        result.put("lifecycleState", apiKey.getLifecycleState() == null
                ? null
                : apiKey.getLifecycleState().getValue());
        result.put("inactiveStatus", apiKey.getInactiveStatus());
        return result;
    }

    private static String buildOciConfig(OciClientService oci, String userId, String fingerprint) {
        String tenancyId = oci.getProvider().getTenantId();
        String region = oci.getUser() == null || oci.getUser().getOciCfg() == null
                ? ""
                : StrUtil.blankToDefault(oci.getUser().getOciCfg().getRegion(), "");
        return "[DEFAULT]\n"
                + "user=" + userId + "\n"
                + "fingerprint=" + StrUtil.blankToDefault(fingerprint, "") + "\n"
                + "tenancy=" + StrUtil.blankToDefault(tenancyId, "") + "\n"
                + "region=" + region + "\n"
                + "key_file=./oci_api_key.pem\n";
    }
}
