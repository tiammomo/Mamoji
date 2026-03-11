package com.mamoji.common;

/**
 * Bit-mask based permission constants and helper methods.
 *
 * <p>The application stores permissions in one integer field and combines multiple permission bits
 * through bitwise OR operations.
 */
public class PermissionConstants {

    // Permission bit flags.
    public static final int PERM_NONE = 0;
    public static final int PERM_MANAGE_USERS = 1 << 0;      // 1 - 用户管理
    public static final int PERM_MANAGE_ACCOUNTS = 1 << 1;   // 2 - 账户管理
    public static final int PERM_MANAGE_CATEGORIES = 1 << 2; // 4 - 分类管理
    public static final int PERM_MANAGE_BUDGETS = 1 << 3;    // 8 - 预算管理

    // Default permissions for regular users.
    public static final int DEFAULT_USER_PERMISSIONS = PERM_NONE;

    // Default permissions for administrators.
    public static final int DEFAULT_ADMIN_PERMISSIONS =
        PERM_MANAGE_USERS | PERM_MANAGE_ACCOUNTS | PERM_MANAGE_CATEGORIES | PERM_MANAGE_BUDGETS;

    private PermissionConstants() {
    }

    /**
     * Checks whether all required permission bits are present.
     *
     * @param userPermissions permissions owned by current user, may be {@code null}
     * @param requiredPermission the required permission bit mask
     * @return {@code true} if user has all required bits; otherwise {@code false}
     */
    public static boolean hasPermission(Integer userPermissions, int requiredPermission) {
        if (userPermissions == null) {
            return false;
        }
        return (userPermissions & requiredPermission) == requiredPermission;
    }

    /**
     * Checks whether any one of the required permission bits is present.
     *
     * @param userPermissions permissions owned by current user, may be {@code null}
     * @param requiredPermissions one or multiple required permission bit masks
     * @return {@code true} if user has at least one required permission; otherwise {@code false}
     */
    public static boolean hasAnyPermission(Integer userPermissions, int... requiredPermissions) {
        if (userPermissions == null) {
            return false;
        }
        for (int permission : requiredPermissions) {
            if ((userPermissions & permission) == permission) {
                return true;
            }
        }
        return false;
    }

    /**
     * Converts permission bits into a human-readable label list.
     *
     * @param permissions permission bit mask
     * @return whitespace-separated permission labels
     */
    public static String getPermissionNames(int permissions) {
        StringBuilder builder = new StringBuilder();
        if ((permissions & PERM_MANAGE_USERS) == PERM_MANAGE_USERS) {
            builder.append("用户管理").append(" ");
        }
        if ((permissions & PERM_MANAGE_ACCOUNTS) == PERM_MANAGE_ACCOUNTS) {
            builder.append("账户管理").append(" ");
        }
        if ((permissions & PERM_MANAGE_CATEGORIES) == PERM_MANAGE_CATEGORIES) {
            builder.append("分类管理").append(" ");
        }
        if ((permissions & PERM_MANAGE_BUDGETS) == PERM_MANAGE_BUDGETS) {
            builder.append("预算管理").append(" ");
        }
        return builder.toString().trim();
    }
}
