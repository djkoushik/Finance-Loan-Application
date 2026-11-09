package com.rajarajeshwari.finance.debtor;

import com.rajarajeshwari.finance.auth.Ctx;
import com.rajarajeshwari.finance.support.ApiException;
import com.rajarajeshwari.finance.support.Db;
import com.rajarajeshwari.finance.support.P;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@RestController
public class DebtorController {

    static final String NO_PHOTO = "nophotoavailable.jpg";

    private final Db db;
    private final Path photoDir;

    public DebtorController(Db db, @Value("${finance.photo-dir}") String photoDir) throws IOException {
        this.db = db;
        this.photoDir = Paths.get(photoDir).toAbsolutePath();
        Files.createDirectories(this.photoDir);
    }

    private static final String SELECT_DEBTOR =
            "SELECT d.debtor_id, d.debtor_code, d.debtor_name, d.date_of_join, d.gender, d.email, d.phone,"
                    + " d.guarantor_name, d.guarantor_phone, d.house_no, d.street, d.seq, d.city, d.state, d.pin,"
                    + " d.guarantor_house_no, d.guarantor_street, d.guarantor_city, d.guarantor_state, d.guarantor_pin,"
                    + " d.photo FROM debtors d WHERE d.is_active ";

    // ---------------------------------------------------------------- lookups

    @GetMapping("/api/lookups")
    public Map<String, Object> lookups() {
        Map<String, Object> out = new HashMap<String, Object>();
        out.put("areas", areas());
        out.put("notes", db.list("SELECT note_id, note FROM note_types ORDER BY note_id"));
        out.put("genders", Arrays.asList(P.of("code", "M", "name", "Male"), P.of("code", "F", "name", "Female")));
        return out;
    }

    @GetMapping("/api/areas")
    public List<String> areas() {
        List<Map<String, Object>> rows = db.list(
                "SELECT DISTINCT street FROM debtors d WHERE d.is_active " + Ctx.scopeSql("d") + " ORDER BY street",
                Ctx.withScope(new HashMap<String, Object>()));
        List<String> out = new ArrayList<String>();
        for (Map<String, Object> r : rows) {
            out.add((String) r.get("street"));
        }
        return out;
    }

    // ---------------------------------------------------------------- debtors

    @GetMapping("/api/debtors")
    public List<Map<String, Object>> list(@RequestParam(required = false) String area,
                                          @RequestParam(required = false) String q) {
        Map<String, Object> p = Ctx.withScope(new HashMap<String, Object>());
        StringBuilder sql = new StringBuilder(SELECT_DEBTOR).append(Ctx.scopeSql("d"));
        if (P.blankToNull(area) != null) {
            sql.append(" AND d.street = :area");
            p.put("area", area.trim());
        }
        if (P.blankToNull(q) != null) {
            sql.append(" AND (lower(d.debtor_name) LIKE :q OR lower(d.debtor_code) LIKE :q OR d.phone LIKE :q)");
            p.put("q", "%" + q.trim().toLowerCase(Locale.ROOT) + "%");
        }
        // by Debtor Name only (case-insensitive); the code only breaks ties between identical names
        sql.append(" ORDER BY lower(d.debtor_name), d.debtor_code");
        return db.list(sql.toString(), p);
    }

    @GetMapping("/api/debtors/next-code")
    public Map<String, Object> nextCode() {
        return P.of("code", codeFor(nextId()));
    }

    @GetMapping("/api/debtors/{code}")
    public Map<String, Object> get(@PathVariable String code) {
        return find(code);
    }

    @PostMapping("/api/debtors")
    @Transactional
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        Map<String, Object> p = readAndValidate(body, null);
        db.lockNumbering();
        int id = nextId();
        String code = codeFor(id);
        if (db.count("SELECT count(*) FROM debtors WHERE debtor_code = :c", P.of("c", code)) > 0) {
            throw new ApiException(409, "Debtor code " + code + " already exists");
        }
        Integer seq = db.scalar("SELECT COALESCE(MAX(seq),0)+1 FROM debtors WHERE street = :s",
                P.of("s", p.get("street")), Integer.class);
        p.put("id", id);
        p.put("code", code);
        p.put("seq", seq);
        p.put("uid", Ctx.user().getUserId());
        db.update("INSERT INTO debtors(debtor_id, debtor_code, debtor_name, date_of_join, gender, email, phone,"
                + " guarantor_name, guarantor_phone, house_no, street, seq, city, state, pin, guarantor_house_no,"
                + " guarantor_street, guarantor_city, guarantor_state, guarantor_pin, photo, user_id)"
                + " VALUES (:id, :code, :name, :doj, :gender, :email, :phone, :gname, :gphone, :hno, :street, :seq,"
                + " :city, :state, :pin, :ghno, :gstreet, :gcity, :gstate, :gpin, '" + NO_PHOTO + "', :uid)", p);
        return find(code);
    }

    @PutMapping("/api/debtors/{code}")
    @Transactional
    public Map<String, Object> update(@PathVariable String code, @RequestBody Map<String, Object> body) {
        find(code);
        Map<String, Object> p = readAndValidate(body, code);
        p.put("code", code);
        db.update("UPDATE debtors SET debtor_name=:name, date_of_join=:doj, gender=:gender, email=:email, phone=:phone,"
                + " guarantor_name=:gname, guarantor_phone=:gphone, house_no=:hno, street=:street, city=:city,"
                + " state=:state, pin=:pin, guarantor_house_no=:ghno, guarantor_street=:gstreet,"
                + " guarantor_city=:gcity, guarantor_state=:gstate, guarantor_pin=:gpin"
                + " WHERE debtor_code=:code AND is_active", p);
        return find(code);
    }

    @DeleteMapping("/api/debtors/{code}")
    @Transactional
    public Map<String, Object> delete(@PathVariable String code, @RequestBody Map<String, Object> body) {
        find(code);
        String reason = P.required(body, "reason", "Reason");
        long open = db.count("SELECT count(*) FROM deposits WHERE debtor_code = :c AND is_active", P.of("c", code));
        if (open > 0) {
            throw new ApiException(409, "This debtor still has " + open
                    + " deposit account(s). Delete or close those accounts first.");
        }
        db.update("UPDATE debtors SET is_active = false, delete_reason = :r, deleted_by = :u WHERE debtor_code = :c",
                P.of("r", reason, "u", Ctx.user().getUsername(), "c", code));
        return P.of("ok", true);
    }

    // ---------------------------------------------------------------- photos

    @PostMapping("/api/debtors/{code}/photo")
    public Map<String, Object> uploadPhoto(@PathVariable String code, @RequestParam("file") MultipartFile file)
            throws IOException {
        find(code);
        if (file.isEmpty()) {
            throw new ApiException(400, "Please choose a picture");
        }
        if (file.getSize() > 5L * 1024 * 1024) {
            throw new ApiException(400, "The picture is too large (maximum 5 MB)");
        }
        String ext = extension(file.getOriginalFilename());
        if (!Arrays.asList("jpg", "jpeg", "png", "gif").contains(ext)) {
            throw new ApiException(400, "Only JPG, PNG or GIF pictures are allowed");
        }
        String name = UUID.randomUUID().toString() + "." + ext;
        Files.copy(file.getInputStream(), photoDir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        db.update("UPDATE debtors SET photo = :p WHERE debtor_code = :c", P.of("p", name, "c", code));
        return P.of("photo", name);
    }

    @GetMapping("/photos/{name:.+}")
    public ResponseEntity<Resource> photo(@PathVariable String name) {
        File f = photoDir.resolve(Paths.get(name).getFileName().toString()).toFile();
        if (!f.isFile()) {
            return ResponseEntity.notFound().build();
        }
        String ext = extension(name);
        MediaType type = "png".equals(ext) ? MediaType.IMAGE_PNG
                : "gif".equals(ext) ? MediaType.IMAGE_GIF : MediaType.IMAGE_JPEG;
        return ResponseEntity.ok().contentType(type).body(new FileSystemResource(f));
    }

    // ---------------------------------------------------------------- area visiting order

    @GetMapping("/api/areas/{street}/debtors")
    public List<Map<String, Object>> areaDebtors(@PathVariable String street) {
        Map<String, Object> p = Ctx.withScope(P.of("s", street));
        return db.list("SELECT d.debtor_id, d.debtor_code, d.debtor_name, d.date_of_join, d.phone"
                + " FROM debtors d WHERE d.is_active AND d.street = :s " + Ctx.scopeSql("d")
                + " ORDER BY d.debtor_name ASC, d.debtor_code", p);
    }

    // ---------------------------------------------------------------- helpers

    private Map<String, Object> find(String code) {
        Map<String, Object> p = Ctx.withScope(P.of("c", code));
        Map<String, Object> row = db.one(SELECT_DEBTOR + Ctx.scopeSql("d") + " AND d.debtor_code = :c", p);
        if (row == null) {
            throw new ApiException(404, "Debtor " + code + " not found");
        }
        return row;
    }

    private int nextId() {
        Integer n = db.scalar("SELECT COALESCE(MAX(debtor_id),0)+1 FROM debtors", P.of(), Integer.class);
        return n == null ? 1 : n;
    }

    static String codeFor(int id) {
        return id <= 9 ? "D000" + id : "D00" + id;
    }

    /** Validates the form fields; excludeCode is the debtor being edited (for the duplicate-name check). */
    private Map<String, Object> readAndValidate(Map<String, Object> b, String excludeCode) {
        Map<String, Object> p = new HashMap<String, Object>();
        String name = P.required(b, "debtorName", "Debtor name");
        if (name.length() > 50) {
            throw new ApiException(400, "Debtor name can be at most 50 characters");
        }
        p.put("name", name);
        p.put("doj", P.date(b, "dateOfJoin", "Date of joining"));
        String gender = P.required(b, "gender", "Gender").toUpperCase(Locale.ROOT);
        if (!"M".equals(gender) && !"F".equals(gender)) {
            throw new ApiException(400, "Gender must be Male or Female");
        }
        p.put("gender", gender);
        String email = P.str(b, "email");
        if (!email.isEmpty() && !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new ApiException(400, "Email address is not valid");
        }
        p.put("email", email.isEmpty() ? null : email);
        p.put("phone", phone(P.required(b, "phone", "Cell number"), "Cell number"));
        String gphone = P.str(b, "guarantorPhone");
        p.put("gphone", gphone.isEmpty() ? "" : phone(gphone, "Guarantor cell number"));
        p.put("gname", P.str(b, "guarantorName"));
        p.put("hno", P.str(b, "houseNo"));
        p.put("street", P.required(b, "street", "Colony/Street (area)"));
        p.put("city", P.str(b, "city"));
        p.put("state", P.str(b, "state"));
        p.put("pin", pin(P.str(b, "pin"), "Pin"));
        p.put("ghno", P.str(b, "guarantorHouseNo"));
        p.put("gstreet", P.str(b, "guarantorStreet"));
        p.put("gcity", P.str(b, "guarantorCity"));
        p.put("gstate", P.str(b, "guarantorState"));
        p.put("gpin", pin(P.str(b, "guarantorPin"), "Guarantor pin"));
        for (String k : new String[]{"hno", "street", "city", "state", "ghno", "gstreet", "gcity", "gstate", "gname"}) {
            if (((String) p.get(k)).length() > 100) {
                throw new ApiException(400, "Text fields can be at most 100 characters");
            }
        }
        Map<String, Object> dup = P.of("n", name.toLowerCase(Locale.ROOT));
        String sql = "SELECT count(*) FROM debtors WHERE is_active AND lower(debtor_name) = :n";
        if (excludeCode != null) {
            sql += " AND debtor_code <> :ex";
            dup.put("ex", excludeCode);
        }
        if (db.count(sql, dup) > 0) {
            throw new ApiException(409, "This debtor name already exists");
        }
        return p;
    }

    private static String phone(String v, String label) {
        if (!v.matches("\\d{10}")) {
            throw new ApiException(400, label + " must be exactly 10 digits");
        }
        return v;
    }

    private static int pin(String v, String label) {
        if (v.isEmpty()) {
            return 0;
        }
        if (!v.matches("\\d{6}")) {
            throw new ApiException(400, label + " must be 6 digits");
        }
        return Integer.parseInt(v);
    }

    private static String extension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }
}
