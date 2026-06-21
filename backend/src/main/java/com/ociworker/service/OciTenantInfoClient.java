package com.ociworker.service;

import cn.hutool.core.util.StrUtil;
import com.ociworker.exception.OciException;
import com.ociworker.model.dto.OciProxySnapshot;
import com.ociworker.model.entity.OciUser;
import com.ociworker.util.OciRegionCatalog;
import com.ociworker.util.socks.OciSocksApacheConnectionManager;
import com.oracle.bmc.ClientConfiguration;
import com.oracle.bmc.Region;
import com.oracle.bmc.auth.SimpleAuthenticationDetailsProvider;
import com.oracle.bmc.http.ClientConfigurator;
import com.oracle.bmc.http.client.ProxyConfiguration;
import com.oracle.bmc.http.client.StandardClientProperties;
import com.oracle.bmc.http.client.jersey3.ApacheClientProperties;
import com.oracle.bmc.identity.IdentityClient;
import com.oracle.bmc.ospgateway.SubscriptionServiceClient;
import com.oracle.bmc.tenantmanagercontrolplane.SubscriptionClient;
import lombok.Getter;
import org.apache.http.conn.HttpClientConnectionManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.FileInputStream;
import java.util.Optional;

@Getter
class OciTenantInfoClient implements Closeable {

    private final SimpleAuthenticationDetailsProvider provider;
    private final IdentityClient identityClient;
    private final SubscriptionServiceClient ospClient;
    private final SubscriptionClient organizationSubscriptionClient;
    private final String compartmentId;
    private final HttpClientConnectionManager socksPoolingManager;

    OciTenantInfoClient(OciUser user) {
        if (user == null) {
            throw new OciException("配置不存在");
        }
        Region region = resolveRegion(user.getOciRegion());
        this.provider = SimpleAuthenticationDetailsProvider.builder()
                .tenantId(user.getOciTenantId())
                .userId(user.getOciUserId())
                .fingerprint(user.getOciFingerprint())
                .privateKeySupplier(() -> {
                    try (FileInputStream fis = new FileInputStream(user.getOciKeyPath());
                         ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                        byte[] buffer = new byte[1024];
                        int bytesRead;
                        while ((bytesRead = fis.read(buffer)) != -1) {
                            baos.write(buffer, 0, bytesRead);
                        }
                        return new ByteArrayInputStream(baos.toByteArray());
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to read private key");
                    }
                })
                .region(region)
                .build();

        ClientConfiguration clientConfig = ClientConfiguration.builder()
                .connectionTimeoutMillis(5_000)
                .readTimeoutMillis(8_000)
                .build();
        OciProxyConfigService proxyService = OciProxyConfigService.instance();
        OciProxySnapshot snapshot = proxyService == null ? null : proxyService.snapshot();
        if (proxyService == null || !proxyService.ociUsesExplicitClientProxy()) {
            OciProxyConfigService.clearInProcessHttpSocksProxySystemProperties();
        }

        org.apache.http.impl.conn.PoolingHttpClientConnectionManager socksPool =
                snapshot != null && snapshot.usesSocksForOci()
                        ? OciSocksApacheConnectionManager.create(snapshot)
                        : null;
        this.socksPoolingManager = socksPool;

        Optional<ProxyConfiguration> proxyConfiguration = proxyService == null
                ? Optional.empty()
                : proxyService.getOciProxyConfiguration();
        ClientConfigurator configurator;
        if (socksPool != null) {
            configurator = b -> {
                b.property(ApacheClientProperties.CONNECTION_MANAGER, socksPool);
                b.property(ApacheClientProperties.CONNECTION_MANAGER_SHARED, Boolean.TRUE);
            };
        } else if (proxyConfiguration.isPresent()) {
            ProxyConfiguration pc = proxyConfiguration.get();
            configurator = c -> c.property(StandardClientProperties.PROXY, pc);
        } else {
            configurator = OciProxyConfigService.ociSdkJerseyDirectConfigurator();
        }

        var identityBuilder = IdentityClient.builder().configuration(clientConfig);
        identityBuilder.additionalClientConfigurator(configurator);
        this.identityClient = identityBuilder.build(provider);

        var ospBuilder = SubscriptionServiceClient.builder().configuration(clientConfig);
        ospBuilder.additionalClientConfigurator(configurator);
        this.ospClient = ospBuilder.build(provider);

        var organizationSubscriptionBuilder = SubscriptionClient.builder().configuration(clientConfig);
        organizationSubscriptionBuilder.additionalClientConfigurator(configurator);
        this.organizationSubscriptionClient = organizationSubscriptionBuilder.build(provider);

        this.compartmentId = provider.getTenantId();
    }

    private static Region resolveRegion(String regionId) {
        if (StrUtil.isBlank(regionId)) {
            throw new OciException("Region 不能为空");
        }
        return OciRegionCatalog.resolveRegion(regionId);
    }

    @Override
    public void close() {
        try {
            identityClient.close();
        } catch (Exception ignored) {
        }
        try {
            ospClient.close();
        } catch (Exception ignored) {
        }
        try {
            organizationSubscriptionClient.close();
        } catch (Exception ignored) {
        }
        if (socksPoolingManager != null) {
            try {
                socksPoolingManager.shutdown();
            } catch (Exception ignored) {
            }
        }
    }
}
