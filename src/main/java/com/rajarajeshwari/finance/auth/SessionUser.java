package com.rajarajeshwari.finance.auth;

import java.io.Serializable;

public class SessionUser implements Serializable {
    public static final int ADMIN_ROLE = 1;

    private final int userId;
    private final String username;
    private final String displayName;
    private final int roleId;

    public SessionUser(int userId, String username, String displayName, int roleId) {
        this.userId = userId;
        this.username = username;
        this.displayName = displayName;
        this.roleId = roleId;
    }

    public int getUserId() {
        return userId;
    }

    public String getUsername() {
        return username;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getRoleId() {
        return roleId;
    }

    public boolean isAdmin() {
        return roleId == ADMIN_ROLE;
    }
}
