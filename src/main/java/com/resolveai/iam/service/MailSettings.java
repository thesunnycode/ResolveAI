package com.resolveai.iam.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The two things a sent email needs to know that aren't in {@code spring.mail.*}: who it's
 * from, and where the link inside it should point. Kept separate from the mail *server*
 * settings (host/port/credentials, which Spring Boot's own {@code MailProperties} already
 * binds) because these two are read by call sites, not by {@link org.springframework.mail.javamail.JavaMailSender}.
 */
@Component
public class MailSettings {

    private final String from;
    private final String frontendBaseUrl;

    public MailSettings(@Value("${resolveai.mail.from}") String from,
                        @Value("${resolveai.mail.frontend-base-url}") String frontendBaseUrl) {
        this.from = from;
        this.frontendBaseUrl = frontendBaseUrl;
    }

    public String from() { return from; }
    public String frontendBaseUrl() { return frontendBaseUrl; }
}
