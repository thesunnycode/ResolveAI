package com.resolveai.incidents.web.dto;

import java.util.List;

public record DeliveryStatusResponse(Long updateId, Summary summary, List<Failure> failures) {

    public record Summary(int total, int sent, int pending, int failed) {
    }

    public record Failure(Long ticketId, short attempts, String lastError) {
    }
}
