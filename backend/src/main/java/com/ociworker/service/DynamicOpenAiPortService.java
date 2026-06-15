package com.ociworker.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class DynamicOpenAiPortService {

    public static final int MIN_PORT = 30000;
    public static final int MAX_PORT = 39999;
    public static final int LOAD_BALANCE_PORT = 50000;
    private static volatile int configuredLoadBalancePort = LOAD_BALANCE_PORT;

    private final Map<Integer, Connector> connectors = new ConcurrentHashMap<>();
    private volatile org.apache.catalina.Service tomcatService;

    @Value("${ociworker.openaiLoadBalance.port:50000}")
    public void setConfiguredLoadBalancePort(int port) {
        configuredLoadBalancePort = port > 0 ? port : LOAD_BALANCE_PORT;
    }

    @EventListener
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void onWebServerInitialized(WebServerInitializedEvent event) {
        if (event.getWebServer() instanceof TomcatWebServer tomcatWebServer) {
            this.tomcatService = tomcatWebServer.getTomcat().getService();
        }
    }

    public static boolean isManagedPort(int port) {
        return port >= MIN_PORT && port <= MAX_PORT;
    }

    public static boolean isLoadBalancePort(int port) {
        return port == loadBalancePort();
    }

    public static boolean isOpenAiGatewayPort(int port) {
        return isManagedPort(port) || isLoadBalancePort(port);
    }

    public synchronized void startPort(int port) {
        validateManagedPort(port);
        startConnector(port, "multi-account");
    }

    public synchronized void startLoadBalancePort() {
        startConnector(loadBalancePort(), "load-balance");
    }

    public static int loadBalancePort() {
        return configuredLoadBalancePort;
    }

    private void startConnector(int port, String label) {
        if (connectors.containsKey(port)) {
            return;
        }
        org.apache.catalina.Service svc = tomcatService;
        if (svc == null) {
            throw new IllegalStateException("Tomcat service not ready");
        }
        Connector connector = new Connector("org.apache.coyote.http11.Http11NioProtocol");
        connector.setPort(port);
        try {
            svc.addConnector(connector);
            connectors.put(port, connector);
            log.info("OpenAI {} connector started on port {}", label, port);
        } catch (Exception e) {
            try {
                svc.removeConnector(connector);
            } catch (Exception ignored) {
            }
            throw new IllegalStateException("Failed to start port " + port + ": " + e.getMessage(), e);
        }
    }

    public synchronized void stopPort(int port) {
        Connector connector = connectors.remove(port);
        if (connector == null) {
            return;
        }
        org.apache.catalina.Service svc = tomcatService;
        try {
            connector.stop();
        } catch (Exception e) {
            log.warn("Failed to stop OpenAI connector {}: {}", port, e.getMessage());
        }
        try {
            connector.destroy();
        } catch (Exception e) {
            log.warn("Failed to destroy OpenAI connector {}: {}", port, e.getMessage());
        }
        if (svc != null) {
            try {
                svc.removeConnector(connector);
            } catch (Exception ignored) {
            }
        }
        log.info("OpenAI multi-account connector stopped on port {}", port);
    }

    public boolean isRunning(int port) {
        return connectors.containsKey(port);
    }

    public static void validateManagedPort(int port) {
        if (!isManagedPort(port)) {
            throw new IllegalArgumentException("端口必须在 30000-39999 之间");
        }
    }
}
