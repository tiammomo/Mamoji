package com.mamoji.platform.identity.invitation.application;

import com.mamoji.platform.identity.invitation.domain.InvitationTokenDigest;
import com.mamoji.platform.identity.invitation.domain.RegistrationInvitation;
import java.util.List;
import java.util.Optional;

/** Persistence port owned by Platform Identity for registration invitations. */
public interface RegistrationInvitationRepository {
    RegistrationInvitation insert(RegistrationInvitation invitation);

    List<RegistrationInvitation> findAll();

    Optional<RegistrationInvitation> findByTokenForUpdate(InvitationTokenDigest tokenDigest);

    void updateAcceptance(RegistrationInvitation acceptedInvitation);
}
