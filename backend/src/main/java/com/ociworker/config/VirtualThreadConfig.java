package com.ociworker.config;

import jakarta.annotation.PreDestroy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Configuration
public class VirtualThreadConfig {

    public static final ExecutorService VIRTUAL_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    @Bean
    public ExecutorService virtualExecutor() {
        return VIRTUAL_EXECUTOR;
    }

    @PreDestroy
    public void shutdown() {
        try {
            VIRTUAL_EXECUTOR.shutdown();
            if (!VIRTUAL_EXECUTOR.awaitTermination(3, TimeUnit.SECONDS)) {
                VIRTUAL_EXECUTOR.shutdownNow();
            }
        } catch (InterruptedException e) {
            VIRTUAL_EXECUTOR.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
