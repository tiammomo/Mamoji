package com.mamoji.accessmanagement.infrastructure;

import com.mamoji.accessmanagement.application.AdministratorActor;
import com.mamoji.accessmanagement.application.AdministratorAuthenticator;
import com.mamoji.service.support.AccessControlService;
import org.springframework.stereotype.Component;

@Component
public class LegacyAdministratorAuthenticator implements AdministratorAuthenticator {
    private final AccessControlService accessControl;

    public LegacyAdministratorAuthenticator(AccessControlService accessControl) {
        this.accessControl = accessControl;
    }

    @Override
    public AdministratorActor require(String authorization) {
        var user = accessControl.requireAdmin(authorization);
        return new AdministratorActor(user.id, user.nickname);
    }
}
