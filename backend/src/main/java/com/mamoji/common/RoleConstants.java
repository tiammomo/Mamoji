package com.mamoji.common;

/**
 * Role constants and helpers for role checks.
 */
public class RoleConstants {
    public static final int ADMIN = 1;
    public static final int USER = 2;

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_USER = "USER";

    private RoleConstants() {
    }

    /**
     * Converts numeric role code into symbolic role name.
     *
     * @param role numeric role code
     * @return symbolic role name, or {@code UNKNOWN} when code is unsupported
     */
    public static String getRoleName(int role) {
        switch (role) {
            case ADMIN:
                return ROLE_ADMIN;
            case USER:
                return ROLE_USER;
            default:
                return "UNKNOWN";
        }
    }

    /**
     * Checks whether the provided role code represents administrator.
     *
     * @param role numeric role code, may be {@code null}
     * @return {@code true} when role is administrator; otherwise {@code false}
     */
    public static boolean isAdmin(Integer role) {
        return role != null && role == ADMIN;
    }
}
