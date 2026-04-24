package com.ociworker.config;

import org.apache.catalina.connector.Connector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Configuration;

/**
 * 第二监听口：专供 OpenAI 兼容 /v1（面向 New API 等）。面板仍在 {@code server.port}；WebSSH 子进程在 8008，与本口无关。
 */
@Configuration
public class OpenAiApiPortConfig implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {

    private static final Logger log = LoggerFactory.getLogger(OpenAiApiPortConfig.class);

    @Value("${server.port:8818}")
    private int serverPort;

    @Value("${ociworker.openaiApi.port:8080}")
    private int openaiApiPort;

    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        if (openaiApiPort <= 0) {
            return;
        }
        if (openaiApiPort == serverPort) {
            log.info("ociworker.openaiApi.port equals server.port, skip additional Tomcat connector");
            return;
        }
        Connector connector = new Connector("org.apache.coyote.http11.Http11NioProtocol");
        connector.setPort(openaiApiPort);
        factory.addAdditionalTomcatConnectors(connector);
        log.info("OpenAI-compatible API listening on additional port {}", openaiApiPort);
    }
}
