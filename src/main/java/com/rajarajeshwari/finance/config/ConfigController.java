package com.rajarajeshwari.finance.config;

import com.rajarajeshwari.finance.auth.Ctx;
import com.rajarajeshwari.finance.support.ApiException;
import com.rajarajeshwari.finance.support.Db;
import com.rajarajeshwari.finance.support.P;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Config Center. Everyone logged in can read the names (menu, header, profile); only an administrator can change them.
 * Dropdowns: gender (dropdown_values), note (note_types), role (roles). Also the application icon.
 */
@RestController
public class ConfigController {

    private static final Pattern PAGE_KEY = Pattern.compile("^[a-z0-9-]{1,40}$");
    private static final List<String> ROLE_DISPLAY = Arrays.asList("admin", "all", "none");

    private final Db db;
    private final ConfigService config;
    private final Path iconFile;

    public ConfigController(Db db, ConfigService config,
                            @Value("${finance.branding-dir:./data/branding}") String brandingDir) throws IOException {
        this.db = db;
        this.config = config;
        Path dir = Paths.get(brandingDir).toAbsolutePath();
        Files.createDirectories(dir);
        this.iconFile = dir.resolve("app-icon.bin");
    }

    // ---------------------------------------------------------------- app icon

    /** Public (login screen): the custom icon, or the built-in one. */
    @GetMapping("/api/auth/icon")
    public ResponseEntity<byte[]> icon() throws IOException {
        String meta = config.all().get(ConfigService.ICON);
        if (meta == null || !Files.exists(iconFile)) {
            return ResponseEntity.status(302).location(URI.create("/img/app-icon.png")).build();
        }
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(meta.split("[|]")[0]))
                .cacheControl(CacheControl.noCache()).body(Files.readAllBytes(iconFile));
    }

    @PutMapping("/api/config/icon")
    @Transactional
    public Map<String, Object> uploadIcon(@RequestParam("file") MultipartFile file) throws IOException {
        requireAdmin();
        if (file.isEmpty()) {
            throw new ApiException(400, "Please choose an image");
        }
        if (file.getSize() > 1024 * 1024) {
            throw new ApiException(400, "The image must be smaller than 1 MB");
        }
        byte[] bytes = file.getBytes();
        String type = file.getContentType();
        if (!"image/png".equals(type) && !"image/jpeg".equals(type) && !"image/gif".equals(type)) {
            throw new ApiException(400, "Use a PNG, JPG or GIF image");
        }
        if (ImageIO.read(new ByteArrayInputStream(bytes)) == null) {
            throw new ApiException(400, "That file is not a valid image");
        }
        Files.write(iconFile, bytes);
        config.put(ConfigService.ICON, type + "|" + System.currentTimeMillis(), Ctx.user().getUsername());
        return read();
    }

    @DeleteMapping("/api/config/icon")
    @Transactional
    public Map<String, Object> resetIcon() throws IOException {
        requireAdmin();
        Files.deleteIfExists(iconFile);
        config.put(ConfigService.ICON, "", Ctx.user().getUsername());
        return read();
    }

    private static String iconVersion(Map<String, String> all) {
        String meta = all.get(ConfigService.ICON);
        return meta == null || !meta.contains("|") ? "" : meta.split("[|]")[1];
    }

    // ---------------------------------------------------------------- names (header, profile, pages)

    /** Public: the login screen needs the application name before anyone is logged in. */
    @GetMapping("/api/auth/branding")
    public Map<String, Object> branding() {
        return P.of("appName", config.appName(), "iconVersion", iconVersion(config.all()));
    }

    @GetMapping("/api/config")
    public Map<String, Object> read() {
        Map<String, String> all = config.all();
        Map<String, Object> pages = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, String> e : all.entrySet()) {
            if (e.getKey().startsWith(ConfigService.PAGE_PREFIX)) {
                pages.put(e.getKey().substring(ConfigService.PAGE_PREFIX.length()), e.getValue());
            }
        }
        String roleDisplay = all.get(ConfigService.ROLE_DISPLAY);
        return P.of("appName", config.appName(),
                "profileName", all.containsKey(ConfigService.PROFILE_NAME) ? all.get(ConfigService.PROFILE_NAME) : "",
                "roleDisplay", roleDisplay == null ? "admin" : roleDisplay,
                "iconVersion", iconVersion(all),
                "pages", pages);
    }

    @PutMapping("/api/config")
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> save(@RequestBody Map<String, Object> body) {
        requireAdmin();
        String user = Ctx.user().getUsername();
        String appName = P.required(body, "appName", "Application name");
        if (appName.length() > 60) {
            throw new ApiException(400, "Application name can be at most 60 characters");
        }
        String profileName = P.str(body, "profileName");
        if (profileName.length() > 60) {
            throw new ApiException(400, "Profile name can be at most 60 characters");
        }
        String roleDisplay = P.str(body, "roleDisplay");
        if (!ROLE_DISPLAY.contains(roleDisplay)) {
            throw new ApiException(400, "Show role must be one of: admin, all, none");
        }
        config.put(ConfigService.APP_NAME, appName, user);
        config.put(ConfigService.PROFILE_NAME, profileName, user);
        config.put(ConfigService.ROLE_DISPLAY, roleDisplay, user);

        Object pagesObj = body.get("pages");
        if (pagesObj instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) pagesObj).entrySet()) {
                if (!PAGE_KEY.matcher(e.getKey()).matches()) {
                    throw new ApiException(400, "Unknown page '" + e.getKey() + "'");
                }
                String label = e.getValue() == null ? "" : String.valueOf(e.getValue()).trim();
                if (label.length() > 50) {
                    throw new ApiException(400, "Page names can be at most 50 characters");
                }
                config.put(ConfigService.PAGE_PREFIX + e.getKey(), label, user);
            }
        }
        return read();
    }

    // ---------------------------------------------------------------- dropdowns

    @GetMapping("/api/config/dropdowns")
    public List<Map<String, Object>> dropdowns() {
        requireAdmin();
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (String key : new String[]{"gender", "note", "role"}) {
            out.add(group(key));
        }
        return out;
    }

    /** Adds the value, or changes its display text / active flag when the internal value already exists. */
    @PutMapping("/api/config/dropdowns/{key}")
    @Transactional
    public Map<String, Object> saveValue(@PathVariable String key, @RequestBody Map<String, Object> body) {
        requireAdmin();
        String display = P.required(body, "display", "Display value");
        if (display.length() > 100) {
            throw new ApiException(400, "Display value can be at most 100 characters");
        }
        boolean active = !Boolean.FALSE.equals(body.get("active"));
        String internal = P.str(body, "internal");

        if ("gender".equals(key)) {
            if (internal.length() != 1) {
                throw new ApiException(400, "Internal value for gender must be a single letter");
            }
            internal = internal.toUpperCase();
            if (!active && activeCount("gender", internal) == 0) {
                throw new ApiException(400, "Keep at least one gender active");
            }
            db.update("INSERT INTO dropdown_values(dropdown_key, internal_value, display_value, is_active)"
                            + " VALUES ('gender', :i, :d, :a) ON CONFLICT (dropdown_key, internal_value)"
                            + " DO UPDATE SET display_value = EXCLUDED.display_value, is_active = EXCLUDED.is_active",
                    P.of("i", internal, "d", display, "a", active));
        } else if ("note".equals(key)) {
            int id = internal.isEmpty() ? nextId("note_types", "note_id") : intValue(internal);
            db.update("INSERT INTO note_types(note_id, note) VALUES (:i, :d)"
                    + " ON CONFLICT (note_id) DO UPDATE SET note = EXCLUDED.note", P.of("i", id, "d", display));
        } else if ("role".equals(key)) {
            int id = internal.isEmpty() ? nextId("roles", "role_id") : intValue(internal);
            if (id == 1 && !active) {
                throw new ApiException(400, "The administrator role cannot be switched off");
            }
            db.update("INSERT INTO roles(role_id, role_desc, is_active) VALUES (:i, :d, :a)"
                            + " ON CONFLICT (role_id) DO UPDATE SET role_desc = EXCLUDED.role_desc, is_active = EXCLUDED.is_active",
                    P.of("i", id, "d", display, "a", active));
        } else {
            throw new ApiException(404, "Unknown dropdown");
        }
        return group(key);
    }

    @DeleteMapping("/api/config/dropdowns/{key}/{internal}")
    @Transactional
    public Map<String, Object> deleteValue(@PathVariable String key, @PathVariable String internal) {
        requireAdmin();
        if ("role".equals(key) && "1".equals(internal)) {
            throw new ApiException(400, "The administrator role cannot be deleted");
        }
        if (inUse(key, internal) > 0) {
            throw new ApiException(400, "This value is used by existing records. Switch it off instead of deleting it.");
        }
        if ("gender".equals(key)) {
            db.update("DELETE FROM dropdown_values WHERE dropdown_key = 'gender' AND internal_value = :i", P.of("i", internal));
        } else if ("note".equals(key)) {
            db.update("DELETE FROM note_types WHERE note_id = :i", P.of("i", intValue(internal)));
        } else if ("role".equals(key)) {
            db.update("DELETE FROM roles WHERE role_id = :i", P.of("i", intValue(internal)));
        } else {
            throw new ApiException(404, "Unknown dropdown");
        }
        return group(key);
    }

    // ---------------------------------------------------------------- helpers

    private Map<String, Object> group(String key) {
        List<Map<String, Object>> rows;
        String label;
        boolean supportsActive = true;
        boolean numeric = true;
        if ("gender".equals(key)) {
            label = "Gender";
            numeric = false;
            rows = db.list("SELECT internal_value AS internal, display_value AS display, is_active AS active"
                    + " FROM dropdown_values WHERE dropdown_key = 'gender' ORDER BY internal_value");
        } else if ("note".equals(key)) {
            label = "Deposit note";
            supportsActive = false;
            rows = db.list("SELECT note_id::text AS internal, note AS display, true AS active FROM note_types ORDER BY note_id");
        } else {
            label = "Login role";
            rows = db.list("SELECT role_id::text AS internal, role_desc AS display, is_active AS active FROM roles ORDER BY role_id");
        }
        for (Map<String, Object> r : rows) {
            String internal = (String) r.get("internal");
            r.put("inUse", inUse(key, internal));
            r.put("locked", "role".equals(key) && "1".equals(internal));
        }
        return P.of("key", key, "label", label, "supportsActive", supportsActive, "numeric", numeric, "items", rows);
    }

    /** How many records use the value. */
    private long inUse(String key, String internal) {
        if ("gender".equals(key)) {
            return db.count("SELECT count(*) FROM debtors WHERE gender = :v", P.of("v", internal));
        }
        if ("note".equals(key)) {
            return db.count("SELECT count(*) FROM deposits WHERE note = :v", P.of("v", intValue(internal)));
        }
        if ("role".equals(key)) {
            return db.count("SELECT count(*) FROM users WHERE role_id = :v", P.of("v", intValue(internal)));
        }
        throw new ApiException(404, "Unknown dropdown");
    }

    private long activeCount(String key, String exceptInternal) {
        return db.count("SELECT count(*) FROM dropdown_values WHERE dropdown_key = :k AND is_active AND internal_value <> :i",
                P.of("k", key, "i", exceptInternal));
    }

    private int nextId(String table, String column) {
        Integer v = db.scalar("SELECT coalesce(max(" + column + "), 0) + 1 FROM " + table, P.of(), Integer.class);
        return v == null ? 1 : v;
    }

    private static int intValue(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            throw new ApiException(400, "Internal value must be a whole number");
        }
    }

    private static void requireAdmin() {
        if (!Ctx.user().isAdmin()) {
            throw new ApiException(403, "Only an administrator can change the configuration");
        }
    }
}
