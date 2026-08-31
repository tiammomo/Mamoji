package com.mamoji.accessmanagement.application;

/** Validated access changes passed from the HTTP adapter into the application boundary. */
public record AdminUserAccessCommand(Integer role, Integer permissions) {
}
