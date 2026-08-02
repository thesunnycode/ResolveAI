/**
 * The ticket lifecycle: creation, messages, assignment, the state machine and the append-only event log.
 *
 * <p><b>Owns:</b> {@code ticket}, {@code ticket_message}, {@code ticket_event}, {@code ticket_entity}, {@code attachment}, {@code tenant_sequence}.
 *
 * <p><b>May depend on:</b> {@code iam}, {@code platform}, {@code common}.
 *
 * <p>Publishes {@code TicketCreated}, {@code TicketReplied}, {@code TicketStatusChanged} and {@code TicketResolved} to the outbox. <b>Every other module learns about tickets from those events or by id</b>, never by importing {@code Ticket}.
 */
package com.resolveai.ticketing;
