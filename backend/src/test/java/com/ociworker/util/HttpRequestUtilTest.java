package com.ociworker.util;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class HttpRequestUtilTest {

    @Test
    void publicClientCannotSpoofForwardedAddress() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.10");
        request.addHeader("X-Forwarded-For", "198.51.100.7");

        assertThat(HttpRequestUtil.getClientIp(request)).isEqualTo("203.0.113.10");
    }

    @Test
    void localReverseProxyCanForwardValidatedAddress() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "198.51.100.7, 127.0.0.1");

        assertThat(HttpRequestUtil.getClientIp(request)).isEqualTo("198.51.100.7");
    }

    @Test
    void invalidForwardedAddressFallsBackToProxyAddress() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("172.17.0.2");
        request.addHeader("X-Forwarded-For", "not-an-ip");

        assertThat(HttpRequestUtil.getClientIp(request)).isEqualTo("172.17.0.2");
    }
}
