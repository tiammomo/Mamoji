package com.mamoji.accessmanagement.application;

/** Minimal administrator identity required by access-management use cases. */
public record AdministratorActor(long userId, String nickname) {
}
