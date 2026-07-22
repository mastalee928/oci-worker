package com.ociworker.webssh;

import com.jcraft.jsch.Session;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebSshSessionRegistryTest {

    @Test
    void borrowsRegisteredSessionAndClosesItAfterLeaseRelease() throws Exception {
        Session session = mock(Session.class);
        when(session.isConnected()).thenReturn(true);
        WebSshSessionRegistry registry = new WebSshSessionRegistry();

        String id = registry.register(session, "root");
        String username = registry.withSession(id, (borrowed, owner) -> {
            assertThat(borrowed).isSameAs(session);
            return owner;
        });

        assertThat(id).hasSize(43);
        assertThat(username).isEqualTo("root");
        assertThat(registry.size()).isOne();

        registry.close(id);

        verify(session).disconnect();
        assertThat(registry.size()).isZero();
    }

    @Test
    void waitsForActiveBorrowerBeforeDisconnectingSession() throws Exception {
        Session session = mock(Session.class);
        when(session.isConnected()).thenReturn(true);
        WebSshSessionRegistry registry = new WebSshSessionRegistry();
        String id = registry.register(session, "root");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch leave = new CountDownLatch(1);

        Thread borrower = Thread.startVirtualThread(() -> {
            try {
                registry.withSession(id, (ignored, owner) -> {
                    entered.countDown();
                    leave.await(5, TimeUnit.SECONDS);
                    return owner;
                });
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });

        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        registry.close(id);
        verify(session, never()).disconnect();

        leave.countDown();
        borrower.join(2_000);
        verify(session).disconnect();
    }

    @Test
    void rejectsExpiredLeaseAndUsesInvisibleControlFrame() throws Exception {
        Session session = mock(Session.class);
        when(session.isConnected()).thenReturn(true);
        WebSshSessionRegistry registry = new WebSshSessionRegistry();
        String id = registry.register(session, "root");
        registry.close(id);

        assertThatThrownBy(() -> registry.withSession(id, (ignored, owner) -> owner))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("SSH session has expired");
        assertThat(WebSshSessionRegistry.controlMessage("abc"))
                .isEqualTo("\u001b]777;ociworker-session=abc\u0007");
    }
}
