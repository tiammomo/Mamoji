package com.mamoji.platform.identity.invitation.application;

import com.mamoji.platform.identity.invitation.domain.InvitationTokenDigest;
import com.mamoji.platform.identity.invitation.domain.RegistrationInvitation;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegistrationInvitationService {
    private static final int TOKEN_BYTES = 32;

    private final RegistrationInvitationRepository repository;
    private final SecureRandom secureRandom = new SecureRandom();

    public RegistrationInvitationService(RegistrationInvitationRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public IssuedRegistrationInvitation issue(
        String email,
        int role,
        int permissions,
        OffsetDateTime expiresAt,
        long invitedByUserId,
        OffsetDateTime now
    ) {
        String rawToken = rawToken();
        RegistrationInvitation persisted = repository.insert(RegistrationInvitation.pending(
            InvitationTokenDigest.fromRawToken(rawToken),
            email,
            role,
            permissions,
            expiresAt,
            invitedByUserId,
            now
        ));
        return new IssuedRegistrationInvitation(persisted, rawToken);
    }

    @Transactional(readOnly = true)
    public List<RegistrationInvitation> list() {
        return repository.findAll();
    }

    @Transactional
    public Optional<RegistrationInvitation> findUsableForUpdate(
        String rawToken,
        String email,
        OffsetDateTime now
    ) {
        return InvitationTokenDigest.tryFromRawToken(rawToken)
            .flatMap(repository::findByTokenForUpdate)
            .filter(invitation -> invitation.canBeAcceptedBy(email, now));
    }

    @Transactional
    public RegistrationInvitation accept(
        RegistrationInvitation invitation,
        long acceptedUserId,
        OffsetDateTime acceptedAt
    ) {
        RegistrationInvitation accepted = invitation.accept(acceptedUserId, acceptedAt);
        repository.updateAcceptance(accepted);
        return accepted;
    }

    private String rawToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
