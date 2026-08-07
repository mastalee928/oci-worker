package com.ociworker.bastion;

import com.oracle.bmc.bastion.model.Session;
import com.oracle.bmc.core.model.InstanceAgentConfig;
import com.oracle.bmc.core.model.InstanceAgentPluginConfigDetails;
import com.ociworker.mapper.OciCreateTaskMapper;
import com.ociworker.mapper.OciUserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class BastionServiceTest {

    private BastionService service;

    @BeforeEach
    void setUp() {
        OciUserMapper userMapper = mock(OciUserMapper.class);
        OciCreateTaskMapper taskMapper = mock(OciCreateTaskMapper.class);
        service = spy(new BastionService(userMapper, taskMapper));
        doNothing().when(service).deleteRemoteSession(anyString(), anyString(), anyString());
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @Test
    void claimsTokenOnceAndKeepsWrongOwnerFromConsumingIt() {
        String token = service.registerLease("panel-admin", spec(System.currentTimeMillis() + 60_000));

        assertThatThrownBy(() -> service.claim(token, "other-account"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong");

        BastionService.BastionLease lease = service.claim(token, "panel-admin");
        assertThat(lease.spec().sessionId()).isEqualTo("session-1");
        assertThatThrownBy(() -> service.claim(token, "panel-admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expired");

        service.release(lease);
        service.release(lease);
        verify(service).deleteRemoteSession("tenant-1", "us-phoenix-1", "session-1");
    }

    @Test
    void expiresAndDeletesUnclaimedRemoteSession() {
        String token = service.registerLease("panel-admin", spec(System.currentTimeMillis() - 1));

        assertThatThrownBy(() -> service.claim(token, "panel-admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expired");
        verify(service).deleteRemoteSession("tenant-1", "us-phoenix-1", "session-1");
    }

    @Test
    void extractsBastionEndpointFromManagedSessionCommand() {
        Session session = Session.builder()
                .id("session-1")
                .sshMetadata(Map.of(
                        "command",
                        "ssh -o ProxyCommand=\"ssh -p 22 ocid1.session@host.bastion.us-phoenix-1.oci.oraclecloud.com -W %h:%p\" -p 22 opc@10.0.0.8"))
                .build();

        BastionService.Endpoint endpoint = BastionService.resolveEndpoint(session, "us-phoenix-1");

        assertThat(endpoint.host()).isEqualTo("host.bastion.us-phoenix-1.oci.oraclecloud.com");
        assertThat(endpoint.user()).isEqualTo("ocid1.session");
        assertThat(endpoint.port()).isEqualTo(22);
    }

    @Test
    void prefersExplicitSshMetadata() {
        Session session = Session.builder()
                .id("session-2")
                .sshMetadata(Map.of(
                        "host", "host.bastion.eu-frankfurt-1.oci.oraclecloud.com",
                        "user", "ocid1.session",
                        "port", "2200"))
                .build();

        BastionService.Endpoint endpoint = BastionService.resolveEndpoint(session, "us-phoenix-1");

        assertThat(endpoint.host()).isEqualTo("host.bastion.eu-frankfurt-1.oci.oraclecloud.com");
        assertThat(endpoint.user()).isEqualTo("ocid1.session");
        assertThat(endpoint.port()).isEqualTo(2200);
    }

    @Test
    void treatsUnspecifiedCloudAgentPluginAsEnabledByDefault() {
        InstanceAgentConfig agent = InstanceAgentConfig.builder()
                .areAllPluginsDisabled(false)
                .isManagementDisabled(false)
                .pluginsConfig(List.of())
                .build();

        assertThat(BastionService.isBastionPluginEnabled(agent, "Bastion")).isTrue();
    }

    @Test
    void honorsExplicitCloudAgentPluginDisable() {
        InstanceAgentConfig agent = InstanceAgentConfig.builder()
                .pluginsConfig(List.of(InstanceAgentPluginConfigDetails.builder()
                        .name("Bastion")
                        .desiredState(InstanceAgentPluginConfigDetails.DesiredState.Disabled)
                        .build()))
                .build();

        assertThat(BastionService.isBastionPluginEnabled(agent, "Bastion")).isFalse();
    }

    private static BastionConnectionSpec spec(long expiresAt) {
        return new BastionConnectionSpec(
                "tenant-1", "us-phoenix-1", "session-1",
                "host.bastion.us-phoenix-1.oci.oraclecloud.com", 22,
                "ocid1.session", "private-key",
                "10.0.0.8", 22, "root", 0, "password", null, null,
                "instance-1", expiresAt);
    }
}
