package com.ociworker.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecureRandomUtilTest {

    @Test
    void randomDigitsKeepsFixedLengthAndNumericChars() {
        for (int i = 0; i < 100; i++) {
            String code = SecureRandomUtil.randomDigits(6);
            assertThat(code).hasSize(6).matches("\\d{6}");
        }
    }

    @Test
    void randomDigitsWithInstanceSecretStillKeepsSixDigits() {
        String instanceSecret = SecureRandomUtil.randomHex(64);
        for (int i = 0; i < 100; i++) {
            String code = SecureRandomUtil.randomDigits(6, instanceSecret);
            assertThat(code).hasSize(6).matches("\\d{6}");
        }
    }

    @Test
    void randomHexUsesTwoCharsPerByte() {
        assertThat(SecureRandomUtil.randomHex(8)).hasSize(16).matches("[0-9a-f]{16}");
        assertThat(SecureRandomUtil.randomHex(64)).hasSize(128).matches("[0-9a-f]{128}");
    }
}
