package com.ociworker.util;

import cn.hutool.crypto.digest.DigestUtil;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class CommonUtilsTokenTest {

    @Test
    void v2TokenIsRandomSignedAndTamperResistant() {
        String secret = PanelPasswordHasher.hash("safe-password");
        String first = CommonUtils.generateToken("admin", secret);
        String second = CommonUtils.generateToken("admin", secret);

        assertThat(first).startsWith("v2.").isNotEqualTo(second);
        assertThat(CommonUtils.validateToken(first, "admin", secret)).isTrue();
        assertThat(CommonUtils.validateToken(first + "x", "admin", secret)).isFalse();
        assertThat(CommonUtils.validateToken(first, "other", secret)).isFalse();
    }

    @Test
    void legacyTimeSlotTokenRemainsValidDuringUpgradeWindow() {
        String account = "admin";
        String secret = DigestUtil.sha256Hex("legacy-password");
        long slot = System.currentTimeMillis() / (1000L * 60 * 60 * 24);
        String raw = account + ":" + secret + ":" + slot;
        String legacy = Base64.getEncoder().encodeToString(DigestUtil.sha256(raw));

        assertThat(CommonUtils.validateToken(legacy, account, secret)).isTrue();
    }
}
