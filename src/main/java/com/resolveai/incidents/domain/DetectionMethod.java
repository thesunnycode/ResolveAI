package com.resolveai.incidents.domain;

/** How an incident came to exist. Matches {@code ck_incident_method}. */
public enum DetectionMethod {
    /** The correlation sweep's gate proposed it. */
    CLUSTER,
    /** A human created it directly, or linked the first ticket to a manually-opened one. */
    MANUAL
}
