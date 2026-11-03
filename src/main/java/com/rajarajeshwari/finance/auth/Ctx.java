package com.rajarajeshwari.finance.auth;

import com.rajarajeshwari.finance.support.ApiException;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/** Access to the logged-in user of the current request. */
public final class Ctx {
    static final String ATTR = "finance.user";

    private Ctx() {
    }

    public static SessionUser user() {
        RequestAttributes ra = RequestContextHolder.getRequestAttributes();
        Object u = ra == null ? null : ra.getAttribute(ATTR, RequestAttributes.SCOPE_REQUEST);
        if (u == null) {
            throw new ApiException(401, "Please log in");
        }
        return (SessionUser) u;
    }

    /** Non-admin users only see their own debtors; admins see everything (null = no restriction). */
    public static Integer scopeUserId() {
        SessionUser u = user();
        return u.isAdmin() ? null : u.getUserId();
    }

    /** SQL fragment restricting a debtors alias to the current user; empty for admins. */
    public static String scopeSql(String debtorAlias) {
        return scopeUserId() == null ? "" : " AND " + debtorAlias + ".user_id = :scopeUid ";
    }

    /** Adds the :scopeUid parameter used by {@link #scopeSql}. */
    public static java.util.Map<String, Object> withScope(java.util.Map<String, Object> params) {
        Integer uid = scopeUserId();
        if (uid != null) {
            params.put("scopeUid", uid);
        }
        return params;
    }
}
