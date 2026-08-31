package com.mamoji.accessmanagement.application;

/** Authentication port that keeps legacy session models outside the application boundary. */
public interface AdministratorAuthenticator {
    AdministratorActor require(String authorization);
}
