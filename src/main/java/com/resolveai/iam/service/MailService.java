package com.resolveai.iam.service;

import com.resolveai.iam.domain.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * The one email this product sends: a team invite. Plain text, no template engine - a
 * single message type does not earn one.
 *
 * <p>A failed send is logged, not thrown: the {@code Invite} row was already committed, and
 * the admin can see it as "pending" and re-share the link manually rather than the whole
 * request failing after the database write already happened.
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final JavaMailSender sender;
    private final MailSettings settings;

    public MailService(JavaMailSender sender, MailSettings settings) {
        this.sender = sender;
        this.settings = settings;
    }

    public void sendInvite(String toEmail, String tenantName, Role role, String inviteLink) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(settings.from());
        message.setTo(toEmail);
        message.setSubject("You're invited to join " + tenantName + " on ResolveAI");
        message.setText("""
                You've been invited to join %s on ResolveAI as %s.

                Set up your account here: %s

                This link expires in 7 days. If you weren't expecting this, you can ignore it.
                """.formatted(tenantName, role.name(), inviteLink));
        try {
            sender.send(message);
        } catch (Exception e) {
            log.error("Failed to send invite email to {}", toEmail, e);
        }
    }
}
