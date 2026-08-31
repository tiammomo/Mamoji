package com.mamoji.platform.identity;

/** Pending invitation used to register an enterprise member. */
public class RegistrationInvite {
    public long id;
    public String token;
    public String email;
    public int role;
    public int permissions;
    public String expiresAt;
    public String acceptedAt;
    public Long acceptedUserId;
    public long invitedByUserId;
    public String createdAt;
    public String updatedAt;
}
