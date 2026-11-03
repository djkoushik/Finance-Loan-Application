package com.rajarajeshwari.finance.admin;

import com.rajarajeshwari.finance.support.ApiException;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Backup = zip with data/&lt;table&gt;.csv for every table, photos/ and manifest.txt (row counts).
 * Restore replaces all data with the contents of such a zip, inside one transaction, after saving an
 * automatic safety backup of the current data.
 */
@Service
public class BackupService {

    /** Parents first so the foreign keys are satisfied while loading. */
    static final String[] TABLES = {"roles", "users", "note_types", "debtors", "deposits", "payments"};
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final DataSource dataSource;
    private final Path photoDir;
    private final Path backupDir;

    public BackupService(DataSource dataSource, @Value("${finance.photo-dir}") String photoDir,
                         @Value("${finance.backup-dir}") String backupDir) throws IOException {
        this.dataSource = dataSource;
        this.photoDir = Paths.get(photoDir).toAbsolutePath();
        this.backupDir = Paths.get(backupDir).toAbsolutePath();
        Files.createDirectories(this.photoDir);
        Files.createDirectories(this.backupDir);
    }

    public static String fileName() {
        return "finance-backup-" + LocalDateTime.now().format(STAMP) + ".zip";
    }

    // ------------------------------------------------------------------ backup

    public byte[] createBackup() throws SQLException, IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Map<String, Long> counts = new LinkedHashMap<String, Long>();
        try (ZipOutputStream zip = new ZipOutputStream(bytes); Connection c = dataSource.getConnection()) {
            // one consistent snapshot of all tables
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY");
            }
            CopyManager cm = c.unwrap(PGConnection.class).getCopyAPI();
            for (String t : TABLES) {
                counts.put(t, count(c, t));
                zip.putNextEntry(new ZipEntry("data/" + t + ".csv"));
                Writer w = new OutputStreamWriter(zip, StandardCharsets.UTF_8) {
                    @Override
                    public void close() throws IOException {
                        flush(); // keep the zip stream open
                    }
                };
                cm.copyOut("COPY " + t + " TO STDOUT WITH (FORMAT csv, HEADER true)", w);
                w.flush();
                zip.closeEntry();
            }
            c.rollback();
            File[] photos = photoDir.toFile().listFiles();
            if (photos != null) {
                for (File f : photos) {
                    if (f.isFile()) {
                        zip.putNextEntry(new ZipEntry("photos/" + f.getName()));
                        Files.copy(f.toPath(), zip);
                        zip.closeEntry();
                    }
                }
            }
            StringBuilder m = new StringBuilder("Sri Raja Rajeshwari Finance backup\n")
                    .append("created=").append(LocalDateTime.now()).append('\n');
            for (Map.Entry<String, Long> e : counts.entrySet()) {
                m.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            }
            zip.putNextEntry(new ZipEntry("manifest.txt"));
            zip.write(m.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    // ------------------------------------------------------------------ restore

    /** Replaces all data with the backup. Returns a human-readable summary. */
    public Map<String, Object> restore(InputStream upload) throws SQLException, IOException {
        Package pkg = readZip(upload);

        // safety net: keep a copy of what is being replaced
        String safety = "pre-restore-" + fileName();
        Files.write(backupDir.resolve(safety), createBackup());

        Map<String, Long> loaded = new LinkedHashMap<String, Long>();
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                CopyManager cm = c.unwrap(PGConnection.class).getCopyAPI();
                try (Statement st = c.createStatement()) {
                    st.execute("TRUNCATE payments, deposits, debtors, note_types, users, roles RESTART IDENTITY");
                }
                for (String t : TABLES) {
                    String csv = pkg.csv.get(t);
                    cm.copyIn("COPY " + t + "(" + columnList(c, t, csv) + ") FROM STDIN WITH (FORMAT csv, HEADER true)",
                            new StringReader(csv));
                    loaded.put(t, count(c, t));
                }
                for (Map.Entry<String, Long> e : pkg.manifest.entrySet()) {
                    Long got = loaded.get(e.getKey());
                    if (got != null && !got.equals(e.getValue())) {
                        throw new ApiException(400, "The backup file is damaged: table " + e.getKey() + " should have "
                                + e.getValue() + " rows but " + got + " were read. Nothing was changed.");
                    }
                }
                realignCounters(c);
                c.commit();
            } catch (ApiException e) {
                c.rollback();
                throw e;
            } catch (Exception e) {
                c.rollback();
                throw new ApiException(400, "The backup file could not be restored (" + e.getMessage()
                        + "). Nothing was changed.");
            }
        }
        int photos = restorePhotos(pkg.photos);
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("rows", loaded);
        out.put("photos", photos);
        out.put("safetyBackup", backupDir.resolve(safety).toString());
        return out;
    }

    private void realignCounters(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            for (String[] s : new String[][]{{"debtors", "debtor_id"}, {"deposits", "deposit_id"}, {"payments", "payment_id"}}) {
                st.execute("SELECT setval(pg_get_serial_sequence('" + s[0] + "','" + s[1] + "'), (SELECT COALESCE(MAX("
                        + s[1] + "),0)+1 FROM " + s[0] + "), false)");
            }
        }
    }

    private int restorePhotos(Map<String, byte[]> photos) throws IOException {
        int n = 0;
        for (Map.Entry<String, byte[]> e : photos.entrySet()) {
            Path p = photoDir.resolve(e.getKey()).normalize();
            if (!p.getParent().equals(photoDir)) {
                continue;
            }
            Files.copy(new ByteArrayInputStream(e.getValue()), p, StandardCopyOption.REPLACE_EXISTING);
            n++;
        }
        return n;
    }

    /** Header of the csv must name real columns of the table (guards against odd or hostile files). */
    private String columnList(Connection c, String table, String csv) throws SQLException, IOException {
        Set<String> real = new HashSet<String>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT column_name FROM information_schema.columns WHERE table_schema='public' AND table_name='"
                     + table + "'")) {
            while (rs.next()) {
                real.add(rs.getString(1));
            }
        }
        String header = new BufferedReader(new StringReader(csv)).readLine();
        if (header == null) {
            throw new ApiException(400, "data/" + table + ".csv is empty");
        }
        List<String> cols = new ArrayList<String>();
        for (String h : header.replace("﻿", "").split(",")) {
            String name = h.trim().replace("\"", "");
            if (!real.contains(name)) {
                throw new ApiException(400, "data/" + table + ".csv has an unknown column '" + name
                        + "'. This file was not made by this application.");
            }
            cols.add(name);
        }
        return String.join(", ", cols);
    }

    private static long count(Connection c, String table) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT count(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    // ------------------------------------------------------------------ zip reading

    private static final class Package {
        final Map<String, String> csv = new HashMap<String, String>();
        final Map<String, byte[]> photos = new LinkedHashMap<String, byte[]>();
        final Map<String, Long> manifest = new LinkedHashMap<String, Long>();
    }

    private static final long MAX_ENTRY = 1024L * 1024 * 1024; // 1 GB per file, guards against zip bombs

    private Package readZip(InputStream upload) throws IOException {
        Package p = new Package();
        Set<String> tables = new HashSet<String>(java.util.Arrays.asList(TABLES));
        try (ZipInputStream zin = new ZipInputStream(upload)) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) {
                    continue;
                }
                String name = e.getName().replace('\\', '/');
                byte[] data = readBounded(zin);
                if (name.startsWith("data/") && name.endsWith(".csv")) {
                    String table = name.substring(5, name.length() - 4);
                    if (tables.contains(table)) {
                        p.csv.put(table, new String(data, StandardCharsets.UTF_8));
                    }
                } else if (name.startsWith("photos/")) {
                    String f = name.substring(7);
                    if (f.matches("[A-Za-z0-9._-]+\\.(?i:jpg|jpeg|png|gif)")) {
                        p.photos.put(f, data);
                    }
                } else if (name.equals("manifest.txt")) {
                    for (String line : new String(data, StandardCharsets.UTF_8).split("\\r?\\n")) {
                        int i = line.indexOf('=');
                        if (i > 0 && tables.contains(line.substring(0, i))) {
                            try {
                                p.manifest.put(line.substring(0, i), Long.parseLong(line.substring(i + 1).trim()));
                            } catch (NumberFormatException ignored) {
                                // not a count line
                            }
                        }
                    }
                }
            }
        } catch (java.util.zip.ZipException ex) {
            throw new ApiException(400, "That file is not a valid backup zip");
        }
        if (p.csv.isEmpty() && p.photos.isEmpty() && p.manifest.isEmpty()) {
            throw new ApiException(400, "That file is not a valid backup zip");
        }
        for (String t : TABLES) {
            if (!p.csv.containsKey(t)) {
                throw new ApiException(400, "That is not a backup of this application: data/" + t + ".csv is missing");
            }
        }
        return p;
    }

    private static byte[] readBounded(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > MAX_ENTRY) {
                throw new ApiException(400, "Backup file is too large");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    @SuppressWarnings("unused")
    private static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
