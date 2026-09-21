package com.resolveai.common.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Administrators only.
 *
 * <p>A named annotation rather than a repeated SpEL string. {@code @PreAuthorize} takes a
 * string that is parsed at runtime, so a typo - {@code hasRole('ADMN')} - compiles, deploys,
 * and denies everyone, or worse, is written as {@code hasRole('ROLE_ADMIN')} and silently
 * matches nothing because {@code hasRole} adds the prefix itself. Writing it once here means
 * the rest of the project cannot make either mistake.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@PreAuthorize("hasRole('ADMIN')")
public @interface IsAdmin {
}
