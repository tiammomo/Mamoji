package com.mamoji.platform.identity;

import com.fasterxml.jackson.annotation.JsonIgnore;

/** Authenticated user identity and public profile projection. */
public class User {
    public long id;
    public String email;
    public String nickname;
    public String avatar;
    public Long familyId;
    public int role;
    public int permissions;
    public String createdAt;
    public String updatedAt;

    @JsonIgnore
    public String passwordHash;
}
