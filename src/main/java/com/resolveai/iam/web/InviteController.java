package com.resolveai.iam.web;

import com.resolveai.iam.service.InviteService;
import com.resolveai.iam.web.dto.AcceptInviteRequest;
import com.resolveai.iam.web.dto.InvitePreviewResponse;
import com.resolveai.iam.web.dto.TokenResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public: the invitee has no account and no token yet, only the link an admin sent them.
 * {@link com.resolveai.iam.web.AdminTeamController} is where an admin creates the invite in
 * the first place.
 */
@RestController
@RequestMapping("/api/v1/invites")
public class InviteController {

    private final InviteService inviteService;

    public InviteController(InviteService inviteService) {
        this.inviteService = inviteService;
    }

    @GetMapping("/{token}")
    public InvitePreviewResponse preview(@PathVariable String token) {
        return inviteService.previewInvite(token);
    }

    @PostMapping("/{token}/accept")
    public TokenResponse accept(@PathVariable String token,
                                @Valid @RequestBody AcceptInviteRequest request) {
        return inviteService.acceptInvite(token, request);
    }
}
