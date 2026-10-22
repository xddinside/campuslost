package com.lostfound.security;

import com.lostfound.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct filter-level coverage: the auth rate limit keys buckets on the
 * socket address, so a caller-rotated X-Forwarded-For cannot mint a fresh
 * bucket per attempt.
 */
class RateLimitFilterTest {

    private final RateLimitFilter filter = new RateLimitFilter(new ObjectMapper(), 5, 60, 3, 300);

    private MockHttpServletResponse attempt(String remoteAddr, String forwardedFor) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setRemoteAddr(remoteAddr);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void rotatingForwardedForCannotBypassTheLimit() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(attempt("203.0.113.77", "10.99.0." + i).getStatus())
                    .as("attempt %d within the limit", i)
                    .isEqualTo(HttpServletResponse.SC_OK);
        }
        MockHttpServletResponse limited = attempt("203.0.113.77", "10.99.0.99");
        assertThat(limited.getStatus()).isEqualTo(429);
        ErrorResponse body = new ObjectMapper().readValue(
                limited.getContentAsString(), ErrorResponse.class);
        assertThat(body.code()).isEqualTo("RATE_LIMITED");
        assertThat(limited.getHeader("Retry-After")).isNotBlank();
    }

    @Test
    void differentSocketAddressesGetSeparateBuckets() throws Exception {
        for (int i = 0; i < 5; i++) {
            attempt("203.0.113.77", null);
        }
        assertThat(attempt("203.0.113.77", null).getStatus()).isEqualTo(429);
        // A genuinely different client still gets its own allowance.
        assertThat(attempt("198.51.100.42", null).getStatus()).isEqualTo(200);
    }

    @Test
    void unrelatedPathsAreNotFiltered() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/items");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
