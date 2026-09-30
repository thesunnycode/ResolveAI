package com.resolveai.iam.service;

import com.resolveai.iam.domain.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * The two emails this product sends: a team invite, and a password reset link. Plain text,
 * no template engine - two message types don't earn one either.
 *
 * <p>A failed send is logged, not thrown: the underlying row (an {@code Invite}, or a
 * {@code PasswordResetToken}) was already committed, so a bounced email fails silently to
 * the caller rather than surfacing a request failure after the database write already
 * happened - and for password reset specifically, the response must look identical whether
 * the send succeeded or not (see {@link PasswordResetService}, which never reveals whether an
 * account exists).
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

    public void sendPasswordReset(String toEmail, String tenantName, String resetLink) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(settings.from());
        message.setTo(toEmail);
        message.setSubject("Reset your password for " + tenantName + " on ResolveAI");
        message.setText("""
                Someone requested a password reset for your %s account on ResolveAI.

                Set a new password here: %s

                This link expires in 1 hour and can only be used once. If you didn't request
                this, you can ignore it - your password will not change.
                """.formatted(tenantName, resetLink));
        try {
            sender.send(message);
        } catch (Exception e) {
            log.error("Failed to send password reset email to {}", toEmail, e);
        }
    }
}
