package com.ociworker.util;

import cn.hutool.crypto.digest.DigestUtil;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PanelPasswordHasherTest {

    @Test
    void modernHashIsSaltedAndVerifiesPassword() {
        String first = PanelPasswordHasher.hash("correct horse battery staple");
        String second = PanelPasswordHasher.hash("correct horse battery staple");

        assertThat(first).isNotEqualTo(second);
        assertThat(PanelPasswordHasher.matches("correct horse battery staple", first)).isTrue();
        assertThat(PanelPasswordHasher.matches("wrong", first)).isFalse();
        assertThat(PanelPasswordHasher.needsUpgrade(first)).isFalse();
    }

    @Test
    void legacySha256RemainsCompatibleButRequiresUpgrade() {
        String legacy = DigestUtil.sha256Hex("legacy-password");

        assertThat(PanelPasswordHasher.matches("legacy-password", legacy)).isTrue();
        assertThat(PanelPasswordHasher.matches("wrong", legacy)).isFalse();
        assertThat(PanelPasswordHasher.needsUpgrade(legacy)).isTrue();
    }
}
