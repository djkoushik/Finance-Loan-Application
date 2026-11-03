package com.rajarajeshwari.finance.report;

import com.rajarajeshwari.finance.auth.Ctx;
import com.rajarajeshwari.finance.support.ApiException;
import com.rajarajeshwari.finance.support.Db;
import com.rajarajeshwari.finance.support.P;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Read-only reports. Every endpoint returns a plain list of rows; the UI adds totals. */
@RestController
@org.springframework.web.bind.annotation.RequestMapping("/api/reports")
public class ReportController {

    private final Db db;

    public ReportController(Db db) {
        this.db = db;
    }

    /** " AND d.street = :area" when an area was chosen (also fills the param). */
    private static String areaClause(String area, Map<String, Object> p) {
        if (P.blankToNull(area) == null) {
            return "";
        }
        p.put("area", area.trim());
        return " AND d.street = :area";
    }

    private static String order(String order) {
        return "account".equals(order) ? " ORDER BY t.account_no" : " ORDER BY t.debtor_name, t.account_no";
    }

    private static void checkRange(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new ApiException(400, "To date cannot be before From date");
        }
    }

    // ------------------------------------------------------------------ advances

    /** Advances given on one date. */
    @GetMapping("/advance-on-date")
    public List<Map<String, Object>> advanceOnDate(@RequestParam String date, @RequestParam(required = false) String area) {
        Map<String, Object> p = Ctx.withScope(P.of("date", P.parseDate(date, "Date")));
        String sql = "SELECT dp.debtor_code, d.debtor_name, d.street, dp.account_no, dp.amount_of_advance, dp.day_of_payment,"
                + " dp.date_of_advance, dp.closing_date, nt.note AS note_text"
                + " FROM deposits dp JOIN debtors d ON d.debtor_code = dp.debtor_code AND d.is_active"
                + " LEFT JOIN note_types nt ON nt.note_id = dp.note"
                + " WHERE dp.is_active AND dp.date_of_advance = :date " + Ctx.scopeSql("d") + areaClause(area, p)
                + " ORDER BY d.street, d.seq NULLS LAST, dp.account_no";
        return db.list(sql, p);
    }

    /** Total advanced per day between two dates. */
    @GetMapping("/advance-between")
    public List<Map<String, Object>> advanceBetween(@RequestParam String from, @RequestParam String to,
                                                    @RequestParam(required = false) String area) {
        LocalDate f = P.parseDate(from, "From date");
        LocalDate t = P.parseDate(to, "To date");
        checkRange(f, t);
        Map<String, Object> p = Ctx.withScope(P.of("from", f, "to", t));
        return db.list("SELECT dp.date_of_advance, COUNT(*) AS accounts, SUM(dp.amount_of_advance) AS advance_amount"
                + " FROM deposits dp JOIN debtors d ON d.debtor_code = dp.debtor_code AND d.is_active"
                + " WHERE dp.is_active AND dp.date_of_advance BETWEEN :from AND :to " + Ctx.scopeSql("d") + areaClause(area, p)
                + " GROUP BY dp.date_of_advance ORDER BY dp.date_of_advance", p);
    }

    // ------------------------------------------------------------------ collections

    /** Payments received on one date (optionally one area). */
    @GetMapping("/paid-on-date")
    public List<Map<String, Object>> paidOnDate(@RequestParam String date, @RequestParam(required = false) String area) {
        Map<String, Object> p = Ctx.withScope(P.of("date", P.parseDate(date, "Date")));
        return db.list("SELECT p.debtor_code, d.debtor_name, d.street, p.account_no, p.day_date, p.amount"
                + " FROM payments p JOIN debtors d ON d.debtor_code = p.debtor_code AND d.is_active"
                + " WHERE p.is_active AND p.day_date = :date " + Ctx.scopeSql("d") + areaClause(area, p)
                + " ORDER BY d.street, d.seq NULLS LAST, p.account_no", p);
    }

    /** Per-day comparison of money lent out (payments) and money collected (receipts). */
    @GetMapping("/payments-receipts")
    public List<Map<String, Object>> paymentsAndReceipts(@RequestParam String from, @RequestParam String to,
                                                         @RequestParam(required = false) String area) {
        LocalDate f = P.parseDate(from, "From date");
        LocalDate t = P.parseDate(to, "To date");
        checkRange(f, t);
        Map<String, Object> p = Ctx.withScope(P.of("from", f, "to", t));
        String scope = Ctx.scopeSql("d") + areaClause(area, p);
        return db.list("SELECT COALESCE(a.day, r.day) AS day, COALESCE(a.amt,0) AS advance_amount,"
                + " COALESCE(r.amt,0) AS received_amount FROM"
                + " (SELECT dp.date_of_advance AS day, SUM(dp.amount_of_advance) AS amt FROM deposits dp"
                + "   JOIN debtors d ON d.debtor_code = dp.debtor_code AND d.is_active"
                + "   WHERE dp.is_active AND dp.date_of_advance BETWEEN :from AND :to " + scope + " GROUP BY dp.date_of_advance) a"
                + " FULL JOIN (SELECT pm.day_date AS day, SUM(pm.amount) AS amt FROM payments pm"
                + "   JOIN debtors d ON d.debtor_code = pm.debtor_code AND d.is_active"
                + "   WHERE pm.is_active AND pm.day_date BETWEEN :from AND :to " + scope + " GROUP BY pm.day_date) r ON a.day = r.day"
                + " ORDER BY 1", p);
    }

    // ------------------------------------------------------------------ dues

    /** Every running account with what has been paid up to the date and what is still due. */
    @GetMapping("/total-due")
    public List<Map<String, Object>> totalDue(@RequestParam String date, @RequestParam(required = false) String area) {
        Map<String, Object> p = Ctx.withScope(P.of("date", P.parseDate(date, "Date")));
        return db.list("SELECT t.* FROM (SELECT dp.debtor_code, d.debtor_name, d.street, dp.account_no, dp.date_of_advance,"
                + " dp.amount_of_advance, COALESCE(pa.paid,0) AS paid, dp.amount_of_advance - COALESCE(pa.paid,0) AS due"
                + " FROM deposits dp JOIN debtors d ON d.debtor_code = dp.debtor_code AND d.is_active"
                + " LEFT JOIN (SELECT debtor_code, account_no, SUM(amount) AS paid FROM payments"
                + "   WHERE is_active AND day_date <= :date GROUP BY debtor_code, account_no) pa"
                + "   ON pa.debtor_code = dp.debtor_code AND pa.account_no = dp.account_no"
                + " WHERE dp.is_active AND dp.account_status = 'OPEN' AND dp.date_of_advance <= :date "
                + Ctx.scopeSql("d") + areaClause(area, p) + ") t ORDER BY t.street, t.debtor_name, t.account_no", p);
    }

    /** Running accounts with number of days elapsed since the advance. */
    @GetMapping("/days")
    public List<Map<String, Object>> days(@RequestParam String date, @RequestParam(defaultValue = "name") String order) {
        Map<String, Object> p = Ctx.withScope(P.of("date", P.parseDate(date, "Date")));
        return db.list("SELECT t.* FROM (SELECT dp.debtor_code, d.debtor_name, dp.account_no, dp.date_of_advance,"
                + " dp.amount_of_advance, COALESCE(pa.paid,0) AS paid, dp.amount_of_advance - COALESCE(pa.paid,0) AS due,"
                + " (CAST(:date AS date) - dp.date_of_advance) AS no_of_days"
                + " FROM deposits dp JOIN debtors d ON d.debtor_code = dp.debtor_code AND d.is_active"
                + " LEFT JOIN (SELECT debtor_code, account_no, SUM(amount) AS paid FROM payments"
                + "   WHERE is_active AND day_date <= :date GROUP BY debtor_code, account_no) pa"
                + "   ON pa.debtor_code = dp.debtor_code AND pa.account_no = dp.account_no"
                + " WHERE dp.is_active AND dp.account_status = 'OPEN' AND dp.date_of_advance <= :date "
                + Ctx.scopeSql("d") + ") t" + order(order), p);
    }

    /** Collections within a period against what was outstanding. */
    @GetMapping("/month-due")
    public List<Map<String, Object>> monthDue(@RequestParam String from, @RequestParam String to,
                                              @RequestParam(defaultValue = "name") String order) {
        LocalDate f = P.parseDate(from, "From date");
        LocalDate t = P.parseDate(to, "To date");
        checkRange(f, t);
        Map<String, Object> p = Ctx.withScope(P.of("from", f, "to", t));
        return db.list("SELECT t.* FROM (SELECT dp.debtor_code, d.debtor_name, dp.account_no, dp.date_of_advance,"
                + " dp.amount_of_advance, COALESCE(pa.before_from,0) AS paid_before, COALESCE(pa.in_range,0) AS paid_in_period,"
                + " COALESCE(pa.total,0) AS paid_total, dp.amount_of_advance - COALESCE(pa.total,0) AS due"
                + " FROM deposits dp JOIN debtors d ON d.debtor_code = dp.debtor_code AND d.is_active"
                + " LEFT JOIN (SELECT debtor_code, account_no,"
                + "   SUM(amount) FILTER (WHERE day_date < :from) AS before_from,"
                + "   SUM(amount) FILTER (WHERE day_date >= :from) AS in_range, SUM(amount) AS total"
                + "   FROM payments WHERE is_active AND day_date <= :to GROUP BY debtor_code, account_no) pa"
                + "   ON pa.debtor_code = dp.debtor_code AND pa.account_no = dp.account_no"
                + " WHERE dp.is_active AND dp.date_of_advance <= :to " + Ctx.scopeSql("d") + ") t"
                + " WHERE t.due > 0 OR t.paid_in_period > 0" + order(order), p);
    }

    // ------------------------------------------------------------------ ledger

    /** One account's payments in date order with running totals. */
    @GetMapping("/ledger")
    public Map<String, Object> ledger(@RequestParam String debtorCode, @RequestParam int accountNo) {
        Map<String, Object> acc = db.one("SELECT dp.deposit_id, dp.debtor_code, d.debtor_name, d.phone, d.guarantor_name,"
                + " d.guarantor_phone, d.house_no || ', ' || d.street || ', ' || d.city || ', ' || d.state || ' - ' || d.pin AS address,"
                + " dp.account_no, dp.amount_of_advance, dp.no_of_days, dp.day_of_payment, dp.date_of_advance, dp.closing_date,"
                + " dp.account_status, nt.note AS note_text"
                + " FROM deposits dp JOIN debtors d ON d.debtor_code = dp.debtor_code AND d.is_active"
                + " LEFT JOIN note_types nt ON nt.note_id = dp.note"
                + " WHERE dp.is_active AND dp.debtor_code = :c AND dp.account_no = :a " + Ctx.scopeSql("d"),
                Ctx.withScope(P.of("c", debtorCode, "a", accountNo)));
        if (acc == null) {
            throw new ApiException(404, "Account not found");
        }
        List<Map<String, Object>> rows = db.list("SELECT day_date, amount,"
                + " SUM(amount) OVER (ORDER BY day_date, payment_id) AS paid_total"
                + " FROM payments WHERE is_active AND debtor_code = :c AND account_no = :a ORDER BY day_date, payment_id",
                P.of("c", debtorCode, "a", accountNo));
        long advance = ((Number) acc.get("amountOfAdvance")).longValue();
        for (Map<String, Object> r : rows) {
            r.put("due", advance - ((Number) r.get("paidTotal")).longValue());
        }
        Map<String, Object> out = new HashMap<String, Object>();
        out.put("account", acc);
        out.put("rows", rows);
        return out;
    }

    // ------------------------------------------------------------------ closed accounts

    private List<Map<String, Object>> closedBetween(LocalDate f, LocalDate t, String area) {
        Map<String, Object> p = Ctx.withScope(P.of("from", f, "to", t));
        return db.list("SELECT t.* FROM (SELECT dp.debtor_code, d.debtor_name, d.street, dp.account_no, dp.date_of_advance,"
                + " dp.amount_of_advance, pa.last_date AS closed_date, (pa.last_date - dp.date_of_advance) AS no_of_days"
                + " FROM deposits dp JOIN debtors d ON d.debtor_code = dp.debtor_code AND d.is_active"
                + " JOIN (SELECT debtor_code, account_no, SUM(amount) AS paid, MAX(day_date) AS last_date FROM payments"
                + "   WHERE is_active GROUP BY debtor_code, account_no) pa"
                + "   ON pa.debtor_code = dp.debtor_code AND pa.account_no = dp.account_no"
                + " WHERE dp.is_active AND pa.paid >= dp.amount_of_advance " + Ctx.scopeSql("d") + areaClause(area, p) + ") t"
                + " WHERE t.closed_date BETWEEN :from AND :to ORDER BY t.closed_date, t.account_no", p);
    }

    /** Accounts fully repaid whose last payment falls in the period. */
    @GetMapping("/closed-between")
    public List<Map<String, Object>> closedBetweenReport(@RequestParam String from, @RequestParam String to,
                                                         @RequestParam(required = false) String area) {
        LocalDate f = P.parseDate(from, "From date");
        LocalDate t = P.parseDate(to, "To date");
        checkRange(f, t);
        return closedBetween(f, t, area);
    }

    private List<Map<String, Object>> closedForDelete(LocalDate f, LocalDate t, String area) {
        Map<String, Object> p = Ctx.withScope(P.of("from", f, "to", t));
        return db.list("SELECT dp.deposit_id, dp.debtor_code, d.debtor_name, d.street, dp.account_no, dp.date_of_advance,"
                + " dp.amount_of_advance, dp.closing_date"
                + " FROM deposits dp JOIN debtors d ON d.debtor_code = dp.debtor_code AND d.is_active"
                + " WHERE dp.is_active AND dp.account_status = 'CLOSED' AND dp.date_of_advance BETWEEN :from AND :to "
                + Ctx.scopeSql("d") + areaClause(area, p) + " ORDER BY dp.account_no", p);
    }

    /** Closed accounts (by date of advance) that could be removed. */
    @GetMapping("/closed-delete")
    public List<Map<String, Object>> closedDeletePreview(@RequestParam String from, @RequestParam String to,
                                                         @RequestParam(required = false) String area) {
        LocalDate f = P.parseDate(from, "From date");
        LocalDate t = P.parseDate(to, "To date");
        checkRange(f, t);
        return closedForDelete(f, t, area);
    }

    /** Removes (soft-deletes) the closed accounts and their payments for the period. */
    @PostMapping("/closed-delete")
    @Transactional
    public Map<String, Object> closedDelete(@RequestBody Map<String, Object> body) {
        LocalDate f = P.date(body, "from", "From date");
        LocalDate t = P.date(body, "to", "To date");
        checkRange(f, t);
        List<Map<String, Object>> rows = closedForDelete(f, t, P.str(body, "area"));
        String why = "Closed accounts cleared " + f + " to " + t;
        String by = Ctx.user().getUsername();
        for (Map<String, Object> r : rows) {
            Map<String, Object> q = P.of("c", r.get("debtorCode"), "a", r.get("accountNo"), "r", why, "u", by);
            db.update("UPDATE payments SET is_active = false, delete_reason = :r, deleted_by = :u"
                    + " WHERE is_active AND debtor_code = :c AND account_no = :a", q);
            db.update("UPDATE deposits SET is_active = false, delete_reason = :r, deleted_by = :u"
                    + " WHERE is_active AND debtor_code = :c AND account_no = :a", q);
        }
        return P.of("deleted", rows.size());
    }

    // ------------------------------------------------------------------ dashboard

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        Map<String, Object> p = Ctx.withScope(P.of("today", LocalDate.now()));
        String s = Ctx.scopeSql("d");
        Map<String, Object> out = new HashMap<String, Object>();
        out.put("debtors", db.count("SELECT count(*) FROM debtors d WHERE d.is_active " + s, p));
        out.put("openAccounts", db.count("SELECT count(*) FROM deposits dp JOIN debtors d ON d.debtor_code = dp.debtor_code AND d.is_active"
                + " WHERE dp.is_active AND dp.account_status = 'OPEN' " + s, p));
        out.put("todayCollection", db.scalar("SELECT COALESCE(SUM(pm.amount),0) FROM payments pm JOIN debtors d ON d.debtor_code = pm.debtor_code"
                + " AND d.is_active WHERE pm.is_active AND pm.day_date = :today " + s, p, Long.class));
        out.put("todayAdvance", db.scalar("SELECT COALESCE(SUM(dp.amount_of_advance),0) FROM deposits dp JOIN debtors d ON d.debtor_code = dp.debtor_code"
                + " AND d.is_active WHERE dp.is_active AND dp.date_of_advance = :today " + s, p, Long.class));
        out.put("outstanding", db.scalar("SELECT COALESCE(SUM(dp.amount_of_advance - COALESCE(pa.paid,0)),0) FROM deposits dp"
                + " JOIN debtors d ON d.debtor_code = dp.debtor_code AND d.is_active"
                + " LEFT JOIN (SELECT debtor_code, account_no, SUM(amount) AS paid FROM payments WHERE is_active"
                + "   GROUP BY debtor_code, account_no) pa ON pa.debtor_code = dp.debtor_code AND pa.account_no = dp.account_no"
                + " WHERE dp.is_active AND dp.account_status = 'OPEN' " + s, p, Long.class));
        return out;
    }
}
