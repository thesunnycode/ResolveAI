package com.resolveai.incidents.domain;

/** One ticket's delivery state for one incident update. Matches {@code ck_delivery_status}. */
public enum DeliveryStatus {
    PENDING,
    SENT,
    FAILED
}
