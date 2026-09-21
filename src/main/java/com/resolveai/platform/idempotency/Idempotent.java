package com.resolveai.platform.idempotency;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a handler whose effect must happen at most once per {@code Idempotency-Key}.
 *
 * <p>Put this on any POST that creates something a duplicate of which would be visible to a
 * human: a second ticket, a second message to a customer, a second incident update. It is
 * <b>not</b> needed on a PUT or a DELETE, which are idempotent by their own definition, and
 * it is not a rate limiter.
 *
 * <p>The behaviour is in {@link IdempotencyAspect}; the contract is doc 05 §4.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Idempotent {
}
