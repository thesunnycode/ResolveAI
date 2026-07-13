package com.resolveai.iam.security;

import com.resolveai.common.error.ApiProblem;
import com.resolveai.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Produces the 401 body.
 *
 * <p>Spring Security rejects unauthenticated requests inside the filter chain, before any
 * controller and therefore before {@code @RestControllerAdvice} can see them. Without this
 * class the API would return RFC 7807 everywhere except the one status a client is most
 * likely to meet first, which is exactly the inconsistency that makes an error contract
 * untrustworthy.
 */
@Component
public class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public ProblemAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        ApiProblem problem = ApiProblem.of(
                ErrorCode.UNAUTHORIZED,
                "Authentication is required. Send a valid bearer token in the Authorization header.",
                request.getRequestURI(),
                traceId());

        response.setStatus(ErrorCode.UNAUTHORIZED.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        // Explicit, because the servlet default is ISO-8859-1 and error details in this
        // API carry rupee signs and em dashes. Without it the bytes on the wire do not
        // match what the JSON says they are.
        response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store");
        // Deliberately no WWW-Authenticate header. It would prompt a browser to show a basic
        // auth dialog for an API that does not use basic auth.
        response.getWriter().write(objectMapper.writeValueAsString(problem));
    }

    static String traceId() {
        String id = MDC.get("traceId");
        return id != null ? id : "unavailable";
    }
}
