package com.lostfound.security;

import tools.jackson.databind.ObjectMapper;
import com.lostfound.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

/** Keeps API authentication failures JSON-shaped for the frontend. */
public final class JsonSecurityHandlers {

    private JsonSecurityHandlers() {
    }

    public static AuthenticationEntryPoint authenticationEntryPoint(ObjectMapper mapper) {
        return (request, response, ex) -> write(mapper, request, response,
                HttpServletResponse.SC_UNAUTHORIZED,
                ErrorResponse.of("UNAUTHORIZED", "Please log in to continue."));
    }

    public static AccessDeniedHandler accessDeniedHandler(ObjectMapper mapper) {
        return (request, response, ex) -> write(mapper, request, response,
                HttpServletResponse.SC_FORBIDDEN,
                ErrorResponse.of("FORBIDDEN", "You do not have permission to do that."));
    }

    private static void write(ObjectMapper mapper,
                              HttpServletRequest request,
                              HttpServletResponse response,
                              int status,
                              ErrorResponse body) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        if (status == HttpServletResponse.SC_UNAUTHORIZED) {
            response.setHeader("WWW-Authenticate", "Bearer");
        }
        response.setHeader("Cache-Control", "no-store");
        mapper.writeValue(response.getWriter(), body);
    }
}
