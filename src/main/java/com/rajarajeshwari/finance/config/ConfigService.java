package com.rajarajeshwari.finance.config;

import com.rajarajeshwari.finance.support.Db;
import com.rajarajeshwari.finance.support.P;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads and writes the Config Center settings (table app_settings). */
@Service
public class ConfigService {

    public static final String APP_NAME = "app.name";
    public static final String PROFILE_NAME = "profile.name";
    public static final String ROLE_DISPLAY = "profile.role_display";
    public static final String ICON = "app.icon";
    public static final String PAGE_PREFIX = "page.";
    public static final String DEFAULT_APP_NAME = "SRI RAJA RAJESHWARI";

    private final Db db;

    public ConfigService(Db db) {
        this.db = db;
    }

    public Map<String, String> all() {
        Map<String, String> out = new LinkedHashMap<String, String>();
        List<Map<String, Object>> rows = db.list("SELECT setting_key, setting_value FROM app_settings ORDER BY setting_key");
        for (Map<String, Object> r : rows) {
            out.put((String) r.get("settingKey"), (String) r.get("settingValue"));
        }
        return out;
    }

    public String appName() {
        String v = all().get(APP_NAME);
        return v == null || v.trim().isEmpty() ? DEFAULT_APP_NAME : v;
    }

    /** Blank value removes the override (the built-in default is used again). */
    public void put(String key, String value, String user) {
        if (value == null || value.trim().isEmpty()) {
            db.update("DELETE FROM app_settings WHERE setting_key = :k", P.of("k", key));
            return;
        }
        db.update("INSERT INTO app_settings(setting_key, setting_value, updated_by, updated_at) VALUES (:k, :v, :u, now())"
                        + " ON CONFLICT (setting_key) DO UPDATE SET setting_value = EXCLUDED.setting_value,"
                        + " updated_by = EXCLUDED.updated_by, updated_at = now()",
                P.of("k", key, "v", value.trim(), "u", user));
    }
}
