package com.resolveai;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * A controller that exists only so a test can prove the error contract is real.
 *
 * <p><b>It lives in {@code src/test}, not {@code src/main}.</b> Doc 08 Task 14 says to delete
 * the temporary controller at the end of the phase; putting it here instead means it cannot
 * ship, cannot be forgotten, and the assertion it enables keeps running forever. A scaffold
 * you have to remember to remove is a scaffold that eventually ships.
 *
 * <p><b>It is picked up by component scan rather than imported by a {@code @TestConfiguration},
 * and getting there took two wrong turns worth recording.</b> As a nested {@code @RestController}
 * registered by an {@code @Bean} it was registered twice — once by the bean method, once by
 * the scan, which is "Ambiguous mapping" and a context that will not start. Downgrading the
 * class annotation to a bare {@code @RequestMapping} stopped the double registration but also
 * stopped it being a handler at all: Spring Framework 7 requires the {@code @Controller}
 * stereotype, so the endpoint 404s. A plain scanned {@code @RestController} is what actually
 * works.
 *
 * <p>The cost is that {@code /api/v1/__test__/echo} exists in every integration-test context.
 * The {@code __test__} segment makes that visible at a glance, and no production path can
 * collide with it.
 */
@RestController
public class TemporaryValidationController {

    @PostMapping("/api/v1/__test__/echo")
    @ResponseStatus(HttpStatus.CREATED)
    EchoRequest echo(@Valid @RequestBody EchoRequest request) {
        return request;
    }

    /** Mirrors the shape of a real create request closely enough to exercise both rules. */
    public record EchoRequest(
            @NotBlank(message = "Subject is required")
            @Size(max = 200, message = "Subject must be at most 200 characters")
            String subject,

            @NotBlank(message = "Body is required")
            @Size(max = 20000, message = "Body must be 1-20000 characters")
            String body) {
    }
}
