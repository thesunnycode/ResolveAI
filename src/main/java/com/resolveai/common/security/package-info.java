/**
 * Method-security meta-annotations.
 *
 * <p><b>They go on service methods, not controllers.</b> A service called from a background
 * worker, a scheduled job or another service never passes through a controller, and a check
 * that lives on the controller would simply not run. Phases 6 to 8 add exactly those
 * callers - the triage worker, the SLA poller, the correlation gate - so annotating the
 * service is what makes the guarantee hold everywhere rather than only over HTTP.
 */
package com.resolveai.common.security;
