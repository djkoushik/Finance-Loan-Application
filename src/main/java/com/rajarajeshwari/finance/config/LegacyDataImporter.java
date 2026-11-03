package com.rajarajeshwari.finance.config;

import org.postgresql.PGConnection;
import org.postgresql.copy.CopyManager;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * One-off import of the CSV files written by migration/export-from-sqlserver.ps1.
 * Run with:  --import-legacy=migration/data
 * Refuses to run if the target tables already contain data.
 */
@Component
@Order(1)
public class LegacyDataImporter implements ApplicationRunner {

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;

    public LegacyDataImporter(DataSource dataSource, JdbcTemplate jdbc) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (args.containsOption("stats")) {
            Map<String, Object> m = jdbc.queryForMap(
                    "SELECT count(*) total, sum(amount) total_amt, count(*) FILTER (WHERE is_active) active,"
                            + " sum(amount) FILTER (WHERE is_active) active_amt FROM payments");
            System.out.println("STATS payments " + m);
            System.out.println("STATS deposits " + jdbc.queryForMap(
                    "SELECT count(*) total, count(*) FILTER (WHERE is_active) active, sum(amount_of_advance) adv,"
                            + " count(*) FILTER (WHERE account_status='OPEN' AND is_active) open_active FROM deposits"));
            System.out.println("STATS debtors " + jdbc.queryForMap(
                    "SELECT count(*) total, count(*) FILTER (WHERE is_active) active FROM debtors"));
        }
        List<String> opt = args.getOptionValues("import-legacy");
        if (opt == null || opt.isEmpty()) {
            return;
        }
        Path dir = Paths.get(opt.get(0)).toAbsolutePath();
        Integer existing = jdbc.queryForObject(
                "SELECT (SELECT count(*) FROM debtors) + (SELECT count(*) FROM payments) + (SELECT count(*) FROM deposits)",
                Integer.class);
        if (existing != null && existing > 0) {
            System.out.println("IMPORT SKIPPED: target database already contains data.");
            return;
        }

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            CopyManager cm = c.unwrap(PGConnection.class).getCopyAPI();

            copy(cm, dir, "roles", "roles(role_id, role_desc, is_active, reason)");
            copy(cm, dir, "note_types", "note_types(note_id, note)");
            importUsers(c, dir);
            copy(cm, dir, "debtors",
                    "debtors(debtor_id, debtor_code, debtor_name, date_of_join, gender, email, phone, guarantor_name,"
                            + " guarantor_phone, house_no, street, seq, city, state, pin, guarantor_house_no, guarantor_street,"
                            + " guarantor_city, guarantor_state, guarantor_pin, photo, user_id, is_active, deleted_by, delete_reason)");
            copy(cm, dir, "deposits",
                    "deposits(deposit_id, debtor_code, account_no, amount_of_advance, no_of_days, day_of_payment,"
                            + " date_of_advance, closing_date, note, account_end_date, account_status, user_id, is_active,"
                            + " deleted_by, delete_reason)");
            // The legacy payment ids contain duplicates (corrupt clustered index in the source database),
            // so payments get fresh ids; nothing else references them.
            try (java.sql.Statement st = c.createStatement()) {
                st.execute("CREATE TEMP TABLE payments_stage (old_id integer, debtor_code varchar(100), account_no integer,"
                        + " day_date date, amount integer, user_id integer, is_active boolean, deleted_by varchar(250),"
                        + " delete_reason varchar(500)) ON COMMIT DROP");
                copy(cm, dir, "payments", "payments_stage");
                st.execute("INSERT INTO payments(debtor_code, account_no, day_date, amount, user_id, is_active, deleted_by, delete_reason)"
                        + " SELECT debtor_code, account_no, day_date, amount, user_id, is_active, deleted_by, delete_reason"
                        + " FROM payments_stage ORDER BY old_id, day_date, debtor_code");
            }
            c.commit();
        }

        // Re-align identity/sequence counters with the imported ids
        jdbc.execute("SELECT setval(pg_get_serial_sequence('debtors','debtor_id'), (SELECT COALESCE(MAX(debtor_id),0)+1 FROM debtors), false)");
        jdbc.execute("SELECT setval(pg_get_serial_sequence('deposits','deposit_id'), (SELECT COALESCE(MAX(deposit_id),0)+1 FROM deposits), false)");
        jdbc.execute("SELECT setval(pg_get_serial_sequence('payments','payment_id'), (SELECT COALESCE(MAX(payment_id),0)+1 FROM payments), false)");
        jdbc.execute("SELECT setval('account_no_seq', (SELECT COALESCE(MAX(account_no),0)+1 FROM deposits), false)");

        for (String t : new String[]{"roles", "users", "note_types", "debtors", "deposits", "payments"}) {
            System.out.println("IMPORTED " + t + ": " + jdbc.queryForObject("SELECT count(*) FROM " + t, Integer.class));
        }
    }

    private void copy(CopyManager cm, Path dir, String file, String target) throws IOException, SQLException {
        Path p = dir.resolve(file + ".csv");
        try (BufferedReader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            cm.copyIn("COPY " + target + " FROM STDIN WITH (FORMAT csv, HEADER true)", r);
        }
    }

    /** Users are inserted row by row so the plain-text legacy passwords can be stored as BCrypt hashes. */
    private void importUsers(Connection c, Path dir) throws IOException, SQLException {
        BCryptPasswordEncoder enc = new BCryptPasswordEncoder();
        List<String> lines = Files.readAllLines(dir.resolve("users.csv"), StandardCharsets.UTF_8);
        for (int i = 1; i < lines.size(); i++) {
            List<String> f = parseCsvLine(lines.get(i));
            try (java.sql.PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO users(user_id, username, password_hash, first_name, last_name, email, role_id) VALUES (?,?,?,?,?,?,?)")) {
                ps.setInt(1, Integer.parseInt(f.get(0)));
                ps.setString(2, f.get(1));
                ps.setString(3, enc.encode(f.get(2)));
                ps.setString(4, emptyToNull(f.get(3)));
                ps.setString(5, emptyToNull(f.get(4)));
                ps.setString(6, emptyToNull(f.get(5)));
                ps.setObject(7, f.get(6).isEmpty() ? null : Integer.valueOf(f.get(6)));
                ps.executeUpdate();
            }
        }
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    /** Minimal CSV line parser for the quoted format written by the export script. */
    static List<String> parseCsvLine(String line) {
        List<String> out = new java.util.ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (q) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                    else q = false;
                } else cur.append(ch);
            } else if (ch == '"') q = true;
            else if (ch == ',') { out.add(cur.toString()); cur.setLength(0); }
            else cur.append(ch);
        }
        out.add(cur.toString());
        return out;
    }
}
