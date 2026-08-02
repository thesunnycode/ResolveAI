package com.resolveai.ticketing.web.dto;

import com.resolveai.iam.domain.AppUser;

/**
 * A person, as they appear inside another resource: an id and a display name.
 *
 * <p><b>No email address.</b> A ticket list is visible to every agent in a tenant, and the
 * requester's address is not needed to render one - it would be a contact list extracted
 * from a queue. Anyone who needs it fetches the user.
 */
public record UserRef(Long id, String fullName) {

    public static UserRef of(AppUser user) {
        return user == null ? null : new UserRef(user.getId(), user.getFullName());
    }
}
