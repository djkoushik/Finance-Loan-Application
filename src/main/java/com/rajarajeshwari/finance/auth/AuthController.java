package com.rajarajeshwari.finance.auth;

import com.rajarajeshwari.finance.support.ApiException;
import com.rajarajeshwari.finance.support.Db;
import com.rajarajeshwari.finance.support.P;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final int MAX_FAILURES = 5;
    private static final long LOCK_MILLIS = 10 * 60 * 1000L;

    private final Db db;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final Map<String, long[]> failures = new ConcurrentHashMap<String, long[]>(); // {count, lockedUntil}

    public AuthController(Db db) {
        this.db = db;
    }

    @GetMapping("/roles")
    public List<Map<String, Object>> roles() {
        return db.list("SELECT role_id, role_desc FROM roles WHERE is_active ORDER BY role_id");
    }

    @GetMapping("/me")
    public Map<String, Object> me(HttpSession session) {
        Object u = session.getAttribute(AuthInterceptor.SESSION_ATTR);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("authenticated", u != null);
        if (u != null) {
            out.put("user", describe((SessionUser) u));
        }
        return out;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        String username = P.required(body, "username", "User id");
        String password = P.required(body, "password", "Password");
        int roleId = P.integer(body, "roleId", "Role");

        String key = username.toLowerCase();
        long[] f = failures.get(key);
        if (f != null && f[1] > System.currentTimeMillis()) {
            throw new ApiException(429, "Too many failed attempts. This account is locked for 10 minutes.");
        }

        Map<String, Object> row = db.one(
                "SELECT user_id, username, password_hash, first_name, last_name, role_id FROM users"
                        + " WHERE lower(username) = lower(:u) AND role_id = :r", P.of("u", username, "r", roleId));
        if (row == null || !encoder.matches(password, (String) row.get("passwordHash"))) {
            long[] cur = failures.containsKey(key) ? failures.get(key) : new long[]{0, 0};
            cur[0]++;
            if (cur[0] >= MAX_FAILURES) {
                cur[1] = System.currentTimeMillis() + LOCK_MILLIS;
                cur[0] = 0;
            }
            failures.put(key, cur);
            throw new ApiException(401, "The username or password you entered is incorrect");
        }
        failures.remove(key);

        String name = (str(row.get("firstName")) + " " + str(row.get("lastName"))).trim();
        SessionUser su = new SessionUser(((Number) row.get("userId")).intValue(), (String) row.get("username"),
                name.isEmpty() ? (String) row.get("username") : name, ((Number) row.get("roleId")).intValue());
        HttpSession old = req.getSession(false);
        if (old != null) {
            old.invalidate(); // new session id after login
        }
        HttpSession session = req.getSession(true);
        session.setMaxInactiveInterval(8 * 60 * 60);
        session.setAttribute(AuthInterceptor.SESSION_ATTR, su);
        return describe(su);
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpSession session) {
        session.invalidate();
        return P.of("ok", true);
    }

    @PostMapping("/change-password")
    public Map<String, Object> changePassword(@RequestBody Map<String, Object> body) {
        SessionUser u = Ctx.user();
        String oldPwd = P.required(body, "oldPassword", "Current password");
        String newPwd = P.required(body, "newPassword", "New password");
        if (newPwd.length() < 4) {
            throw new ApiException(400, "New password must be at least 4 characters");
        }
        String hash = db.scalar("SELECT password_hash FROM users WHERE user_id = :id", P.of("id", u.getUserId()), String.class);
        if (hash == null || !encoder.matches(oldPwd, hash)) {
            throw new ApiException(400, "Current password is incorrect");
        }
        db.update("UPDATE users SET password_hash = :h WHERE user_id = :id",
                P.of("h", encoder.encode(newPwd), "id", u.getUserId()));
        return P.of("ok", true);
    }

    private static Map<String, Object> describe(SessionUser u) {
        return P.of("userId", u.getUserId(), "username", u.getUsername(), "name", u.getDisplayName(),
                "roleId", u.getRoleId(), "admin", u.isAdmin());
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }
}
