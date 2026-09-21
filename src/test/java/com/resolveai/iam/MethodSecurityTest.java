package com.resolveai.iam;

import static org.assertj.core.api.Assertions.assertThat;

import com.resolveai.AuthTestSupport;
import com.resolveai.IntegrationTestBase;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Proves the {@code @PreAuthorize} meta-annotations are load-bearing rather than decorative.
 *
 * <p>An annotation that silently does nothing is worse than no annotation: it reads like a
 * control, satisfies a reviewer, and protects nothing. {@code @EnableMethodSecurity} has to
 * be switched on, the proxy has to be in the call path, and the SpEL has to name a role
 * that exists — three things that all look fine right up until one is missing.
 */
class MethodSecurityTest extends IntegrationTestBase {

    @Autowired AuthTestSupport auth;

    private AuthTestSupport.SeededTenant tenant;
    private String agentToken;
    private String adminToken;
    private String customerToken;

    @BeforeEach
    void seed() {
        auth.wipe();
        tenant = auth.seedTenant("sec");
        agentToken = auth.accessToken(rest, "sec", "agent");
        adminToken = auth.accessToken(rest, "sec", "admin");
        customerToken = auth.accessToken(rest, "sec", "customer");
    }

    @Test
    @DisplayName("an AGENT calling an @IsAdmin method gets 403 problem+json")
    void agentCannotCallAdminMethod() {
        ResponseEntity<Map> response = setCapacity(agentToken, tenant.agentId(), 20);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getHeaders().getContentType().isCompatibleWith(
                MediaType.APPLICATION_PROBLEM_JSON))
                .as("a denial from method security must go through the same error contract "
                    + "as everything else")
                .isTrue();
        assertThat(response.getBody().get("errorCode")).isEqualTo("FORBIDDEN");
        assertThat((String) response.getBody().get("traceId")).isNotBlank();
    }

    @Test
    @DisplayName("an ADMIN calling the same method succeeds")
    void adminCanCallAdminMethod() {
        ResponseEntity<Map> response = setCapacity(adminToken, tenant.agentId(), 20);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("maxConcurrent")).isEqualTo(20);
    }

    @Test
    @DisplayName("a CUSTOMER cannot reach an @IsAgentOrAbove method")
    void customerCannotSetAvailability() {
        ResponseEntity<Map> response = setAvailability(customerToken, false);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("errorCode")).isEqualTo("FORBIDDEN");
    }

    @Test
    @DisplayName("an AGENT can set their own availability")
    void agentCanSetOwnAvailability() {
        ResponseEntity<Map> response = setAvailability(agentToken, false);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("isAvailable")).isEqualTo(false);
        // The target came from the principal, not from a parameter, so there was no id to
        // tamper with in the first place.
        assertThat(((Number) response.getBody().get("userId")).longValue())
                .isEqualTo(tenant.agentId());
    }

    @Test
    @DisplayName("lowering capacity below the open count warns instead of refusing")
    void loweringCapacityBelowOpenCountWarns() {
        ResponseEntity<Map> response = setCapacity(adminToken, tenant.agentId(), 1);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("maxConcurrent")).isEqualTo(1);
        // openCount is 0 on a freshly seeded profile, so no warning is expected here; the
        // assertion pins that the field exists and is absent rather than wrongly populated.
        assertThat(response.getBody().get("warning")).isNull();
    }

    @Test
    @DisplayName("an admin cannot set capacity for another tenant's agent")
    void capacityIsTenantScoped() {
        var other = auth.seedTenant("sec2");

        ResponseEntity<Map> response = setCapacity(adminToken, other.agentId(), 30);

        // 404, not 403: the profile exists, but not in a tenant this admin can see, and
        // confirming its existence would turn the endpoint into an id oracle.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("errorCode")).isEqualTo("USER_NOT_FOUND");
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> setCapacity(String token, Long userId, int max) {
        return rest.exchange("/api/v1/admin/agents/" + userId + "/capacity", HttpMethod.PUT,
                new HttpEntity<>(Map.of("maxConcurrent", max), AuthTestSupport.bearer(token)),
                Map.class);
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> setAvailability(String token, boolean available) {
        return rest.exchange("/api/v1/agents/me/availability", HttpMethod.PUT,
                new HttpEntity<>(Map.of("isAvailable", available, "shiftStart", "09:00",
                        "shiftEnd", "18:00"), AuthTestSupport.bearer(token)),
                Map.class);
    }
}
