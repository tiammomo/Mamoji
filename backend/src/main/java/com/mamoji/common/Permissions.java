package com.mamoji.common;

public final class Permissions {
    public static final int USER = 1;
    public static final int ACCOUNT = 2;
    public static final int CATEGORY = 4;
    public static final int BUDGET = 8;
    public static final int ALL = USER | ACCOUNT | CATEGORY | BUDGET;

    private Permissions() {
    }
}
