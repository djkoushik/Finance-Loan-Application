package com.rajarajeshwari.finance.admin;

import com.rajarajeshwari.finance.support.ApiException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads the finance tables straight out of a native, uncompressed Microsoft SQL Server full backup (.bak)
 * of the legacy RAJARAJESHWARI database, without needing SQL Server.
 *
 * It finds the 8 KB data pages in the file, reads the table/column catalog (sysschobjs, syscolpars,
 * sysrowsets, sysallocunits) to learn where each table lives and how its rows are laid out, decodes the
 * rows and returns them as CSV text in the same format as this application's own backups, so the normal
 * restore code can load them.
 */
final class SqlServerBakReader {

    private static final int PAGE = 8192;
    private static final int CHUNK = 4 * 1024 * 1024;
    private static final Charset ANSI = Charset.forName("windows-1252");

    // object ids of the SQL Server catalog tables as they appear in page headers
    private static final int SYSSCHOBJS = 34;
    private static final int SYSCOLPARS = 41;
    private static final int SYSROWSETS = 5;
    private static final int SYSALLOCUNITS = 7;
    private static final int SYSRSCOLS = 3;

    private final FileChannel ch;
    private long[] pageOffset = new long[0];
    private int[] pageObj = new int[0];

    private SqlServerBakReader(FileChannel ch) {
        this.ch = ch;
    }

    /** True when the file starts with the Microsoft Tape Format header that every native .bak has. */
    static boolean isBak(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] h = new byte[4];
            return in.read(h) == 4 && h[0] == 'T' && h[1] == 'A' && h[2] == 'P' && h[3] == 'E';
        }
    }

    /** Returns csv text per application table (roles, users, note_types, debtors, deposits, payments). */
    static Map<String, String> read(Path file) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            SqlServerBakReader r = new SqlServerBakReader(ch);
            r.indexPages();
            return r.extract();
        }
    }

    // ------------------------------------------------------------------ page index

    private void indexPages() throws IOException {
        long size = ch.size();
        ByteBuffer buf = ByteBuffer.allocate(CHUNK + PAGE).order(ByteOrder.LITTLE_ENDIAN);
        List<long[]> found = new ArrayList<long[]>();
        Set<Long> seen = new HashSet<Long>();
        for (long base = 0; base + PAGE <= size; base += CHUNK) {
            buf.clear();
            while (buf.hasRemaining() && ch.read(buf, base + buf.position()) > 0) {
                // keep reading until the chunk is full or the file ends
            }
            int avail = buf.position();
            for (int o = 0; o + PAGE <= avail && o < CHUNK; o += 512) {
                if (validDataPage(buf, o)) {
                    long key = ((long) (buf.getShort(o + 36) & 0xffff) << 32) | (buf.getInt(o + 32) & 0xffffffffL);
                    if (seen.add(key)) {
                        found.add(new long[]{base + o, buf.getInt(o + 24)});
                    }
                }
            }
        }
        if (found.size() < 8) {
            throw new ApiException(400, "This SQL Server backup cannot be read: it looks compressed or encrypted. "
                    + "Please make the backup without compression and try again.");
        }
        pageOffset = new long[found.size()];
        pageObj = new int[found.size()];
        for (int i = 0; i < found.size(); i++) {
            pageOffset[i] = found.get(i)[0];
            pageObj[i] = (int) found.get(i)[1];
        }
    }

    private static boolean validDataPage(ByteBuffer b, int o) {
        if (b.get(o) != 1 || b.get(o + 1) != 1) {
            return false; // header version 1, page type 1 = data page
        }
        int slots = b.getShort(o + 22) & 0xffff;
        int freeData = b.getShort(o + 30) & 0xffff;
        int fileId = b.getShort(o + 36) & 0xffff;
        if (slots < 1 || slots > 4000 || freeData < 96 || freeData > PAGE || fileId < 1 || fileId > 255) {
            return false;
        }
        if (96 + 2 * slots > PAGE) {
            return false;
        }
        for (int i = 0; i < slots; i++) {
            int so = b.getShort(o + PAGE - 2 * (i + 1)) & 0xffff;
            if (so < 96 || so >= PAGE - 2 * slots) {
                return false;
            }
        }
        return true;
    }

    private interface RowSink {
        void row(Rec r);
    }

    private void eachRow(int objId, RowSink sink) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(PAGE).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < pageOffset.length; i++) {
            if (pageObj[i] != objId) {
                continue;
            }
            buf.clear();
            while (buf.hasRemaining() && ch.read(buf, pageOffset[i] + buf.position()) > 0) {
                // fill
            }
            if (buf.hasRemaining()) {
                continue;
            }
            int slots = buf.getShort(22) & 0xffff;
            for (int s = 0; s < slots; s++) {
                int so = buf.getShort(PAGE - 2 * (s + 1)) & 0xffff;
                int type = (buf.get(so) >> 1) & 7;
                if (type != 0 && type != 1) {
                    continue; // only live rows: 0 = primary, 1 = forwarded (ghost/stub/index records are skipped)
                }
                sink.row(new Rec(buf, so));
            }
        }
    }

    // ------------------------------------------------------------------ record

    /** One data record: fixed part, null bitmap and variable-length part. */
    private static final class Rec {
        final ByteBuffer b;
        final int so;
        final int fixedEnd;
        final int nCols;
        final int bitmap;
        final int varCount;
        final int varEnds;

        Rec(ByteBuffer b, int so) {
            this.b = b;
            this.so = so;
            this.fixedEnd = b.getShort(so + 2) & 0xffff;
            this.nCols = b.getShort(so + fixedEnd) & 0xffff;
            this.bitmap = so + fixedEnd + 2;
            int nb = (nCols + 7) / 8;
            int vcPos = bitmap + nb;
            this.varCount = vcPos + 2 <= PAGE ? b.getShort(vcPos) & 0xffff : 0;
            this.varEnds = vcPos + 2;
        }

        boolean isNull(int ordinal) {
            if (ordinal >= nCols) {
                return true;
            }
            return (b.get(bitmap + ordinal / 8) & (1 << (ordinal % 8))) != 0;
        }

        /** Bytes of the k-th variable-length column, or null when it is not stored. */
        byte[] var(int k) {
            if (k >= varCount) {
                return null;
            }
            int end = b.getShort(varEnds + 2 * k) & 0xffff;
            if ((end & 0x8000) != 0) {
                throw new ApiException(400, "This SQL Server backup holds very large values (off-row data) that this "
                        + "reader does not support.");
            }
            int start = k == 0 ? varEnds + 2 * varCount - so : (b.getShort(varEnds + 2 * (k - 1)) & 0x7fff);
            if (end < start || so + end > PAGE) {
                return null;
            }
            byte[] out = new byte[end - start];
            for (int i = 0; i < out.length; i++) {
                out[i] = b.get(so + start + i);
            }
            return out;
        }

        int i32(int fixedOffset) {
            return b.getInt(so + 4 + fixedOffset);
        }

        long i64(int fixedOffset) {
            return b.getLong(so + 4 + fixedOffset);
        }

        int i16(int fixedOffset) {
            return b.getShort(so + 4 + fixedOffset);
        }

        int u8(int fixedOffset) {
            return b.get(so + 4 + fixedOffset) & 0xff;
        }
    }

    // ------------------------------------------------------------------ catalog

    private static final class Col {
        int colId;
        String name; // null = dropped column (still occupies space in old rows)
        int xtype;
        int length;
        int prec;
        int scale;
        int fixedOffset = -1; // within the fixed part
        int bitIndex = -1;    // for bit columns
        int varIndex = -1;
        int ordinal;
    }

    private static final class TableInfo {
        int objectId;
        String name;
        List<Col> cols = new ArrayList<Col>();
        int dataObjId = -1;
        long rowsetId = -1;
    }

    private Map<String, TableInfo> catalog() throws IOException {
        final Map<Integer, TableInfo> byId = new HashMap<Integer, TableInfo>();
        eachRow(SYSSCHOBJS, new RowSink() {
            public void row(Rec r) {
                String type = new String(new byte[]{(byte) r.u8(13), (byte) r.u8(14)}, StandardCharsets.ISO_8859_1).trim();
                byte[] name = r.var(0);
                if ("U".equals(type) && name != null) {
                    TableInfo t = new TableInfo();
                    t.objectId = r.i32(0);
                    t.name = new String(name, StandardCharsets.UTF_16LE);
                    byId.put(t.objectId, t);
                }
            }
        });
        eachRow(SYSCOLPARS, new RowSink() {
            public void row(Rec r) {
                TableInfo t = byId.get(r.i32(0));
                if (t == null || r.i16(4) != 0) {
                    return;
                }
                Col c = new Col();
                c.colId = r.i32(6);
                c.xtype = r.u8(10);
                c.length = r.i16(15);
                c.prec = r.u8(17);
                c.scale = r.u8(18);
                byte[] name = r.var(0);
                c.name = name == null ? null : new String(name, StandardCharsets.UTF_16LE);
                t.cols.add(c);
            }
        });
        // table -> rowset -> allocation unit -> page object id
        final Map<Long, TableInfo> rowsets = new HashMap<Long, TableInfo>();
        eachRow(SYSROWSETS, new RowSink() {
            public void row(Rec r) {
                TableInfo t = byId.get(r.i32(9));
                int idx = r.i32(13);
                if (t != null && (idx == 0 || idx == 1)) {
                    rowsets.put(r.i64(0), t);
                    t.rowsetId = r.i64(0);
                }
            }
        });
        eachRow(SYSALLOCUNITS, new RowSink() {
            public void row(Rec r) {
                TableInfo t = rowsets.get(r.i64(9));
                if (t != null && r.u8(8) == 1) { // type 1 = in-row data
                    t.dataObjId = (int) ((r.i64(0) >> 16) & 0xffffffffL);
                }
            }
        });
        for (TableInfo t : byId.values()) {
            Collections.sort(t.cols, new Comparator<Col>() {
                public int compare(Col a, Col b) {
                    return Integer.compare(a.colId, b.colId);
                }
            });
        }
        // physical placement of every column (also correct after columns were dropped/added): sysrscols
        final Set<Col> placed = new HashSet<Col>();
        eachRow(SYSRSCOLS, new RowSink() {
            public void row(Rec r) {
                TableInfo t = rowsets.get(r.i64(0));
                if (t == null || t.rowsetId != r.i64(0)) {
                    return;
                }
                int colId = r.i32(8);
                for (Col c : t.cols) {
                    if (c.colId == colId) {
                        int off = r.i32(40) & 0xffff;
                        c.ordinal = r.i32(44) - 1;
                        if (c.xtype == 104) {
                            c.fixedOffset = off - 4;
                            c.bitIndex = r.i16(48);
                        } else if (off >= 0x8000) {
                            c.varIndex = 0x10000 - off - 1;
                        } else {
                            c.fixedOffset = off - 4;
                        }
                        placed.add(c);
                    }
                }
            }
        });
        Map<String, TableInfo> out = new HashMap<String, TableInfo>();
        for (TableInfo t : byId.values()) {
            for (Col c : t.cols) {
                if (!placed.contains(c) && c.name != null) {
                    layout(t); // no sysrscols information: fall back to the computed layout
                    break;
                }
            }
            out.put(t.name.toLowerCase(Locale.ROOT), t);
        }
        return out;
    }

    private static int fixedSize(Col c) {
        switch (c.xtype) {
            case 48: return 1;                 // tinyint
            case 52: return 2;                 // smallint
            case 56: return 4;                 // int
            case 127: return 8;                // bigint
            case 61: return 8;                 // datetime
            case 58: return 4;                 // smalldatetime
            case 40: return 3;                 // date
            case 60: return 8;                 // money
            case 122: return 4;                // smallmoney
            case 62: return 8;                 // float
            case 59: return 4;                 // real
            case 36: return 16;                // uniqueidentifier
            case 189: return 8;                // timestamp
            case 175: return c.length;         // char
            case 239: return c.length;         // nchar
            case 173: return c.length;         // binary
            case 106:
            case 108: return c.prec <= 9 ? 5 : c.prec <= 19 ? 9 : c.prec <= 28 ? 13 : 17; // decimal / numeric
            default: return -1;                // 104 bit handled separately, rest variable
        }
    }

    private static void layout(TableInfo t) {
        int off = 0;
        int bits = 0;
        int var = 0;
        int ord = 0;
        for (Col c : t.cols) {
            c.ordinal = ord++;
            if (c.xtype == 104) {
                c.bitIndex = bits++;
            } else if (fixedSize(c) >= 0) {
                c.fixedOffset = off;
                off += fixedSize(c);
            } else {
                c.varIndex = var++;
            }
        }
        int bitBase = off;
        for (Col c : t.cols) {
            if (c.bitIndex >= 0) {
                c.fixedOffset = bitBase + c.bitIndex / 8;
            }
        }
    }

    // ------------------------------------------------------------------ row decoding

    private Object value(Rec r, Col c) {
        if (r.isNull(c.ordinal)) {
            return null;
        }
        if (c.varIndex >= 0) {
            byte[] v = r.var(c.varIndex);
            if (v == null) {
                return null;
            }
            switch (c.xtype) {
                case 231: return new String(v, StandardCharsets.UTF_16LE);
                case 167: return new String(v, ANSI);
                default: return v;
            }
        }
        switch (c.xtype) {
            case 56: return r.i32(c.fixedOffset);
            case 52: return (int) r.i16(c.fixedOffset);
            case 48: return r.u8(c.fixedOffset);
            case 127: return r.i64(c.fixedOffset);
            case 104: return (r.u8(c.fixedOffset) & (1 << (c.bitIndex % 8))) != 0;
            case 61: return LocalDate.of(1900, 1, 1).plusDays(r.i32(c.fixedOffset + 4));
            case 58: return LocalDate.of(1900, 1, 1).plusDays(r.u8(c.fixedOffset + 2) | (r.u8(c.fixedOffset + 3) << 8));
            case 175: {
                byte[] v = new byte[c.length];
                for (int i = 0; i < v.length; i++) {
                    v[i] = (byte) r.u8(c.fixedOffset + i);
                }
                return new String(v, ANSI).replaceAll("\\s+$", "");
            }
            default: return null;
        }
    }

    private interface ValueSink {
        void row(Map<String, Object> values);
    }

    private void eachTableRow(final TableInfo t, final ValueSink sink) throws IOException {
        eachRow(t.dataObjId, new RowSink() {
            public void row(Rec r) {
                if (r.nCols < 1) {
                    return;
                }
                Map<String, Object> m = new HashMap<String, Object>();
                for (Col c : t.cols) {
                    if (c.name != null) {
                        m.put(c.name.toLowerCase(Locale.ROOT), value(r, c));
                    }
                }
                sink.row(m);
            }
        });
    }

    // ------------------------------------------------------------------ app tables

    private Map<String, String> extract() throws IOException {
        Map<String, TableInfo> cat = catalog();
        String[] legacy = {"roles_master", "users", "dropdown_master", "debtors_master", "dayweise_deposites", "daywise_payments"};
        for (String n : legacy) {
            TableInfo t = cat.get(n);
            if (t == null || t.dataObjId < 0) {
                throw new ApiException(400, "That SQL Server backup is not a backup of the finance database: table "
                        + n.toUpperCase(Locale.ROOT) + " was not found.");
            }
        }
        Map<String, String> out = new LinkedHashMap<String, String>();
        out.put("roles", rolesCsv(cat.get("roles_master")));
        out.put("users", usersCsv(cat.get("users")));
        out.put("note_types", noteTypesCsv(cat.get("dropdown_master")));
        out.put("debtors", simpleCsv(cat.get("debtors_master"),
                "debtor_id,debtor_code,debtor_name,date_of_join,gender,email,phone,guarantor_name,guarantor_phone,house_no,"
                        + "street,seq,city,state,pin,guarantor_house_no,guarantor_street,guarantor_city,guarantor_state,"
                        + "guarantor_pin,photo,user_id,is_active,deleted_by,delete_reason",
                "debtorid,debtorcode,debtorname,dateofjion,gender,email_addr,debtorphone,guarantorname,guarantorphone,debtorhno,"
                        + "debtorstreet,debtorseq,debtorcity,debtorstate,debtorpin,guarantorhno,guarantorstreet,guarantorcity,"
                        + "guarantorstate,guarantorpin,photo,userid,isactive,deletedby,deletereason"));
        out.put("deposits", simpleCsv(cat.get("dayweise_deposites"),
                "deposit_id,debtor_code,account_no,amount_of_advance,no_of_days,day_of_payment,date_of_advance,closing_date,"
                        + "note,account_end_date,account_status,user_id,is_active,deleted_by,delete_reason",
                "depositeid,debtorcode,accountno,amountofadvance,noofdays,dayofpayment,dateofadvance,closingdate,note,"
                        + "accountenddate,accountstatus,userid,isactive,deletedby,deletereason"));
        out.put("payments", paymentsCsv(cat.get("daywise_payments")));
        return out;
    }

    private static void cell(StringBuilder sb, Object v) {
        if (v != null) {
            String s;
            if (v instanceof Boolean) {
                s = ((Boolean) v) ? "t" : "f";
            } else {
                s = String.valueOf(v);
            }
            sb.append('"').append(s.replace("\"", "\"\"")).append('"');
        }
    }

    private String simpleCsv(TableInfo t, String header, String legacyCols) throws IOException {
        final String[] cols = legacyCols.split(",");
        final StringBuilder sb = new StringBuilder(header).append('\n');
        eachTableRow(t, new ValueSink() {
            public void row(Map<String, Object> m) {
                for (int i = 0; i < cols.length; i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    cell(sb, m.get(cols[i]));
                }
                sb.append('\n');
            }
        });
        return sb.toString();
    }

    private String rolesCsv(TableInfo t) throws IOException {
        return simpleCsv(t, "role_id,role_desc,is_active,reason", "roleid,roledesc,isactive,reason");
    }

    /** Legacy passwords are plain text; they are stored as BCrypt hashes, like the old data importer did. */
    private String usersCsv(TableInfo t) throws IOException {
        final BCryptPasswordEncoder enc = new BCryptPasswordEncoder();
        final StringBuilder sb = new StringBuilder("user_id,username,password_hash,first_name,last_name,email,role_id\n");
        eachTableRow(t, new ValueSink() {
            public void row(Map<String, Object> m) {
                Object pw = m.get("password");
                cell(sb, m.get("userid"));
                sb.append(',');
                cell(sb, m.get("username"));
                sb.append(',');
                cell(sb, enc.encode(pw == null ? "" : String.valueOf(pw)));
                sb.append(',');
                cell(sb, m.get("firstname"));
                sb.append(',');
                cell(sb, m.get("lastname"));
                sb.append(',');
                cell(sb, m.get("emailid"));
                sb.append(',');
                cell(sb, m.get("roleid"));
                sb.append('\n');
            }
        });
        return sb.toString();
    }

    /** Same filter as the old export script: only rows that have a real NOTE text and a numeric NOTEID. */
    private String noteTypesCsv(TableInfo t) throws IOException {
        final StringBuilder sb = new StringBuilder("note_id,note\n");
        final Set<Integer> seen = new HashSet<Integer>();
        eachTableRow(t, new ValueSink() {
            public void row(Map<String, Object> m) {
                Object note = m.get("note");
                Object id = m.get("noteid");
                if (note == null || id == null || "NULL".equals(note) || "NULL".equals(id)) {
                    return;
                }
                int noteId;
                try {
                    noteId = Integer.parseInt(String.valueOf(id).trim());
                } catch (NumberFormatException e) {
                    return;
                }
                if (!seen.add(noteId)) {
                    return;
                }
                String text = String.valueOf(note);
                sb.append(noteId).append(',');
                cell(sb, text.length() > 100 ? text.substring(0, 100) : text);
                sb.append('\n');
            }
        });
        return sb.toString();
    }

    /**
     * The legacy payment ids contain duplicates (corrupt clustered index in the source database), so payments get
     * fresh ids numbered in legacy order; nothing else references them.
     */
    private String paymentsCsv(TableInfo t) throws IOException {
        final List<Object[]> rows = new ArrayList<Object[]>();
        eachTableRow(t, new ValueSink() {
            public void row(Map<String, Object> m) {
                rows.add(new Object[]{m.get("daywisepaymentid"), m.get("debtorcode"), m.get("accountno"), m.get("daydate"),
                        m.get("amount"), m.get("userid"), m.get("isactive"), m.get("deletedby"), m.get("deletereason")});
            }
        });
        Collections.sort(rows, new Comparator<Object[]>() {
            @SuppressWarnings("unchecked")
            public int compare(Object[] a, Object[] b) {
                int c = Integer.compare(num(a[0]), num(b[0]));
                if (c == 0) {
                    c = ((Comparable<Object>) a[3]).compareTo(b[3]);
                }
                if (c == 0) {
                    c = String.valueOf(a[1]).compareTo(String.valueOf(b[1]));
                }
                return c;
            }
        });
        StringBuilder sb = new StringBuilder("payment_id,debtor_code,account_no,day_date,amount,user_id,is_active,deleted_by,delete_reason\n");
        int id = 1;
        for (Object[] r : rows) {
            sb.append(id++);
            for (int i = 1; i < r.length; i++) {
                sb.append(',');
                cell(sb, r[i]);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static int num(Object o) {
        return o instanceof Number ? ((Number) o).intValue() : 0;
    }
}
