package com.resolveai.incidents.web.dto;

import jakarta.validation.constraints.NotNull;

public record LinkTicketRequest(@NotNull Long ticketId) {
}
