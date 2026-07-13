package com.resolveai.iam.security;

import com.resolveai.common.error.ApiProblem;
import com.resolveai.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Produces the 403 body for a denial raised inside the filter chain.
 *
 * <p>403 means: you are authenticated, this resource is in your scope, and this <i>action</i>
 * is not permitted. It is never used for a resource the caller cannot see - that is a 404,
 * deliberately, because a 403 would confirm the resource exists and turn every id into a
 * probe.
 */
@Component
public class ProblemAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public ProblemAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException ex) throws IOException {
        ApiProblem problem = ApiProblem.of(
                ErrorCode.FORBIDDEN,
                "You are authenticated, but this action is not permitted for your role.",
                request.getRequestURI(),
                ProblemAuthenticationEntryPoint.traceId());

        response.setStatus(ErrorCode.FORBIDDEN.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        // Explicit, because the servlet default is ISO-8859-1 and error details in this
        // API carry rupee signs and em dashes. Without it the bytes on the wire do not
        // match what the JSON says they are.
        response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(objectMapper.writeValueAsString(problem));
    }
}
