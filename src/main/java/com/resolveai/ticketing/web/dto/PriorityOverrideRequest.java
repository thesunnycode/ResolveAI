package com.resolveai.ticketing.web.dto;

import com.resolveai.ticketing.domain.Priority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * @param reason required, at least ten characters, and recorded as a label.
 *               <b>The minimum length is the point, not the required-ness.</b> A
 *               {@code @NotBlank} reason field becomes a speed bump that everyone types
 *               "x" into within a week, and a table of "x" is a table of nothing. Ten
 *               characters does not guarantee a considered answer, but it does stop the
 *               field degrading into one keystroke - and this row is training data.
 *               <p>The value of the endpoint is being able to tell later whether the
 *               model misread the ticket or the policy is wrong. Without a reason, the
 *               two look identical for ever.
 */
public record PriorityOverrideRequest(

        @NotNull(message = "Priority is required")
        Priority priority,

        @NotBlank(message = "A reason is required for a priority override")
        @Size(min = 10, max = 500,
              message = "Reason must be between 10 and 500 characters")
        String reason) {
}
