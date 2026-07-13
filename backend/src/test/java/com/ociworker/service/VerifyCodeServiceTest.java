package com.ociworker.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VerifyCodeServiceTest {
    @Test
    void duplicateTargetIsOmitted() {
        assertEquals("", VerifyCodeService.formatTargetLine(
                "配置 Oracle 配额保护", "配置 Oracle 配额保护"));
    }

    @Test
    void differentTargetIsPreserved() {
        assertEquals("\n目标：生产租户", VerifyCodeService.formatTargetLine(
                "配置 Oracle 配额保护", "生产租户"));
    }
}
