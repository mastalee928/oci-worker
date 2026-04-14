package com.ociworker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class OciWorkerApplication {
    public static void main(String[] args) {
        SpringApplication.run(OciWorkerApplication.class, args);
    }
}
