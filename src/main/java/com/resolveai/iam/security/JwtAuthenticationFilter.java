package com.resolveai.iam.security;

import com.resolveai.platform.tenant.TenantContext;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Turns a bearer token into an authenticated principal and a tenant.
 *
 * <p><b>The tenant comes from the token and from nowhere else.</b> Not a header, not a path
 * variable, not a request body - any of those would let an authenticated user read another
 * tenant's data by editing a parameter, which is the most common multi-tenancy breach there
 * is and the one this whole design is arranged to prevent.
 *
 * <p><b>A bad token does not throw from here.</b> The filter leaves the context
 * unauthenticated and lets the chain continue; Spring Security's entry point then produces
 * the 401 through {@link ProblemAuthenticationEntryPoint}, so the response is RFC 7807 like
 * every other error. Throwing from a filter escapes the {@code @RestControllerAdvice}
 * entirely and produces a container error page.
 *
 * <p><b>Both the tenant context and the security context are cleared in {@code finally}.</b>
 * Tomcat pools threads. A leaked {@code ThreadLocal} means the next request on that thread
 * inherits this one's tenant - a data breach with no exception and no log line.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        boolean authenticated = false;
        try {
            String header = request.getHeader("Authorization");
            if (header != null && header.startsWith(BEARER)) {
                authenticated = authenticate(header.substring(BEARER.length()).trim(), request);
            }
            chain.doFilter(request, response);
        } finally {
            if (authenticated) {
                TenantContext.clear();
                MDC.remove("tenantId");
                MDC.remove("userId");
            }
            // Spring Security clears its own context in SecurityContextPersistenceFilter,
            // but only for requests that reach it. Clearing here too costs nothing and
            // covers the case where something upstream short-circuits.
            SecurityContextHolder.clearContext();
        }
    }

    private boolean authenticate(String token, HttpServletRequest request) {
        try {
            Jws<Claims> jws = jwtService.parseAndValidate(token);
            ResolvePrincipal principal = jwtService.toPrincipal(jws);

            var authentication = new UsernamePasswordAuthenticationToken(
                    principal, null, List.of(new SimpleGrantedAuthority(principal.role().authority())));
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);

            TenantContext.set(principal.tenantId());

            // Fills the placeholders MdcFilter leaves empty, so every log line from here on
            // says which tenant and which user produced it.
            MDC.put("tenantId", String.valueOf(principal.tenantId()));
            MDC.put("userId", String.valueOf(principal.userId()));
            return true;

        } catch (JwtService.TokenExpiredException e) {
            log.debug("Expired token on {}", request.getRequestURI());
            return false;
        } catch (JwtService.InvalidTokenException e) {
            // Logged at debug, not warn: an invalid token is an ordinary event on a public
            // internet endpoint, and logging every one at warn turns the log into noise that
            // hides the genuine problems.
            log.debug("Rejected token on {}: {}", request.getRequestURI(), e.getMessage());
            return false;
        } catch (RuntimeException e) {
            log.warn("Unexpected failure parsing a token on {}", request.getRequestURI(), e);
            return false;
        }
    }
}
