package com.lostfound.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

/** Prevents private API responses from being stored by browsers or proxies. */
@Component
public class PrivateCacheFilter extends OncePerRequestFilter {

    private static final Pattern PUBLIC_ITEM_DETAIL = Pattern.compile("^/api/items/\\d+$");
    private static final Pattern PUBLIC_PHOTO = Pattern.compile("^/api/photos/[A-Za-z0-9-]+$");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (isPrivate(request)) {
            response.setHeader("Cache-Control", "no-store, private");
            response.setHeader("Pragma", "no-cache");
        }
        filterChain.doFilter(request, response);
    }

    private boolean isPrivate(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/")) {
            return false;
        }
        if (PUBLIC_PHOTO.matcher(path).matches()) {
            return false;
        }
        if ("GET".equalsIgnoreCase(request.getMethod())
                && ("/api/items".equals(path) || PUBLIC_ITEM_DETAIL.matcher(path).matches())) {
            return false;
        }
        return !"/api/auth/login".equals(path) && !"/api/auth/register".equals(path);
    }
}
