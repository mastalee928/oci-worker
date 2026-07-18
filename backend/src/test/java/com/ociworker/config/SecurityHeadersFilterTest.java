package com.ociworker.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityHeadersFilterTest {

    @Test
    void authResponsesAreNotCachedAndSameOriginFramesRemainAvailable() throws Exception {
        SecurityHeadersFilter filter = new SecurityHeadersFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        assertThat(response.getHeader("X-Frame-Options")).isEqualTo("SAMEORIGIN");
        assertThat(response.getHeader("Content-Security-Policy")).contains("frame-ancestors 'self'");
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    }
}
