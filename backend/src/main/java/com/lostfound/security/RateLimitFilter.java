package com.lostfound.security;

import tools.jackson.databind.ObjectMapper;
import com.lostfound.dto.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Small in-memory limiter for the two unauthenticated credential endpoints.
 * The app runs as a single demo instance; a shared store is intentionally out
 * of scope.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private record Key(String address, String path) {
    }

    private final ObjectMapper objectMapper;
    private final int loginAttempts;
    private final long loginWindowSeconds;
    private final int registerAttempts;
    private final long registerWindowSeconds;
    private final Map<Key, Deque<Instant>> hits = new ConcurrentHashMap<>();

    public RateLimitFilter(ObjectMapper objectMapper,
                           @Value("${app.rate-limit.login-attempts:10}") int loginAttempts,
                           @Value("${app.rate-limit.login-window-seconds:60}") long loginWindowSeconds,
                           @Value("${app.rate-limit.register-attempts:5}") int registerAttempts,
                           @Value("${app.rate-limit.register-window-seconds:300}") long registerWindowSeconds) {
        this.objectMapper = objectMapper;
        this.loginAttempts = loginAttempts;
        this.loginWindowSeconds = loginWindowSeconds;
        this.registerAttempts = registerAttempts;
        this.registerWindowSeconds = registerWindowSeconds;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        return !("/api/auth/login".equals(request.getRequestURI())
                || "/api/auth/register".equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        boolean login = "/api/auth/login".equals(path);
        int limit = login ? loginAttempts : registerAttempts;
        long window = login ? loginWindowSeconds : registerWindowSeconds;
        Instant now = Instant.now();
        Instant cutoff = now.minusSeconds(window);
        Key key = new Key(clientAddress(request), path);

        Deque<Instant> queue = hits.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        long retryAfter;
        synchronized (queue) {
            while (!queue.isEmpty() && queue.peekFirst().isBefore(cutoff)) {
                queue.pollFirst();
            }
            if (queue.size() >= limit) {
                Instant oldest = queue.peekFirst();
                retryAfter = Math.max(1, window - (now.getEpochSecond() - oldest.getEpochSecond()));
            } else {
                queue.addLast(now);
                if (hits.size() > 10_000) {
                    hits.entrySet().removeIf(entry -> entry.getValue().isEmpty());
                }
                filterChain.doFilter(request, response);
                return;
            }
        }

        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Retry-After", String.valueOf(retryAfter));
        response.setHeader("Cache-Control", "no-store");
        objectMapper.writeValue(response.getWriter(), ErrorResponse.of(
                "RATE_LIMITED",
                "Too many attempts. Please wait a moment and try again."));
    }

    private String clientAddress(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
