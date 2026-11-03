package com.rajarajeshwari.finance.payment;

import com.rajarajeshwari.finance.auth.Ctx;
import com.rajarajeshwari.finance.support.ApiException;
import com.rajarajeshwari.finance.support.Db;
import com.rajarajeshwari.finance.support.P;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Daily payment entry / edit, and account closing. */
@RestController
public class PaymentController {

    private final Db db;

    public PaymentController(Db db) {
        this.db = db;
    }

    /** Sum of active payments of the account on (day = :date + offset). */
    private static String paidOnDay(int offset) {
        return "(SELECT SUM(p.amount) FROM payments p WHERE p.is_active AND p.account_no = dp.account_no"
                + " AND p.debtor_code = dp.debtor_code AND p.day_date = CAST(:date AS date) + " + offset + ")";
    }

    private static String orderBy(String mode) {
        if ("name".equals(mode)) {
            return " ORDER BY t.debtor_name, t.account_no";
        }
        if ("account".equals(mode)) {
            return " ORDER BY t.account_no";
        }
        return " ORDER BY t.seq NULLS LAST, t.debtor_name, t.account_no";
    }

    // ------------------------------------------------------------------ entry sheet

    /**
     * Open accounts that can receive a payment on {@code date}. mode = area | name | account.
     * Each row carries the last three days' payments, days elapsed, the amount already paid before this date
     * and anything already entered for this date.
     */
    @GetMapping("/api/payments/entry")
    public Map<String, Object> entry(@RequestParam String date, @RequestParam(defaultValue = "area") String mode,
                                     @RequestParam(required = false) String area) {
        LocalDate day = P.parseDate(date, "Date");
        Map<String, Object> p = Ctx.withScope(P.of("date", day));
        StringBuilder where = new StringBuilder();
        if ("area".equals(mode)) {
            if (P.blankToNull(area) == null) {
                throw new ApiException(400, "Please select an area");
            }
            where.append(" AND d.street = :area");
            p.put("area", area.trim());
        }
        String sql = "SELECT t.*, t.amount_of_advance - t.paid_before AS due FROM ("
                + "SELECT dp.debtor_code, d.debtor_name, d.phone, d.street, d.seq, dp.account_no, dp.date_of_advance,"
                + " dp.amount_of_advance, dp.day_of_payment, (CAST(:date AS date) - dp.date_of_advance) AS days,"
                + " COALESCE((SELECT SUM(p.amount) FROM payments p WHERE p.is_active AND p.account_no = dp.account_no"
                + "   AND p.debtor_code = dp.debtor_code AND p.day_date < CAST(:date AS date)),0) AS paid_before,"
                + " " + paidOnDay(0) + " AS today_amount,"
                + " COALESCE(" + paidOnDay(-1) + ",0) AS before1, COALESCE(" + paidOnDay(-2) + ",0) AS before2,"
                + " COALESCE(" + paidOnDay(-3) + ",0) AS before3"
                + " FROM deposits dp JOIN debtors d ON d.debtor_code = dp.debtor_code"
                + " WHERE dp.is_active AND d.is_active AND dp.account_status = 'OPEN'"
                + " AND dp.date_of_advance <= CAST(:date AS date) " + Ctx.scopeSql("d") + where + ") t"
                + orderBy(mode);
        List<Map<String, Object>> rows = db.list(sql, p);
        Map<String, Object> out = new HashMap<String, Object>();
        out.put("rows", rows);
        return out;
    }

    /** Saves the amounts typed on the entry sheet. Blank amounts are ignored. */
    @PostMapping("/api/payments/batch")
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> saveBatch(@RequestBody Map<String, Object> body) {
        LocalDate day = P.date(body, "date", "Date");
        Object entries = body.get("entries");
        if (!(entries instanceof List)) {
            throw new ApiException(400, "No payments to save");
        }
        int saved = 0;
        long total = 0;
        for (Map<String, Object> e : (List<Map<String, Object>>) entries) {
            if (P.str(e, "amount").isEmpty()) {
                continue;
            }
            String code = P.required(e, "debtorCode", "Debtor");
            int accountNo = P.integer(e, "accountNo", "Account number");
            int amount = P.integer(e, "amount", "Amount");
            if (amount < 0) {
                throw new ApiException(400, "Amount cannot be negative");
            }
            Map<String, Object> dep = db.one("SELECT dp.amount_of_advance, dp.account_status, d.debtor_name, dp.date_of_advance"
                    + " FROM deposits dp JOIN debtors d ON d.debtor_code = dp.debtor_code AND d.is_active"
                    + " WHERE dp.is_active AND dp.debtor_code = :c AND dp.account_no = :a " + Ctx.scopeSql("d"),
                    Ctx.withScope(P.of("c", code, "a", accountNo)));
            if (dep == null) {
                throw new ApiException(404, "Account " + accountNo + " was not found");
            }
            String who = dep.get("debtorName") + " (A/C " + accountNo + ")";
            if (!"OPEN".equals(dep.get("accountStatus"))) {
                throw new ApiException(409, "Account of " + who + " is already closed");
            }
            if (day.isBefore(((java.sql.Date) dep.get("dateOfAdvance")).toLocalDate())) {
                throw new ApiException(400, "Payment date is before the date of advance for " + who);
            }
            checkWithinDue(who, code, accountNo, day, amount, ((Number) dep.get("amountOfAdvance")).longValue());
            upsert(code, accountNo, day, amount);
            saved++;
            total += amount;
        }
        return P.of("saved", saved, "total", total);
    }

    private void checkWithinDue(String who, String code, int accountNo, LocalDate day, long amount, long advance) {
        Long paidOther = db.scalar("SELECT COALESCE(SUM(amount),0) FROM payments WHERE is_active AND debtor_code = :c"
                + " AND account_no = :a AND day_date <> :d", P.of("c", code, "a", accountNo, "d", day), Long.class);
        long due = advance - (paidOther == null ? 0 : paidOther);
        if (amount > due) {
            throw new ApiException(400, "Amount for " + who + " exceeds the amount still due (" + due + ")");
        }
    }

    private void upsert(String code, int accountNo, LocalDate day, int amount) {
        Integer existing = db.scalar("SELECT payment_id FROM payments WHERE debtor_code = :c AND account_no = :a"
                + " AND day_date = :d ORDER BY is_active DESC, payment_id LIMIT 1",
                P.of("c", code, "a", accountNo, "d", day), Integer.class);
        if (existing != null) {
            db.update("UPDATE payments SET amount = :amt, is_active = true, delete_reason = NULL, deleted_by = NULL,"
                    + " user_id = :uid WHERE payment_id = :id", P.of("amt", amount, "uid", Ctx.user().getUserId(), "id", existing));
        } else {
            db.update("INSERT INTO payments(debtor_code, account_no, day_date, amount, user_id)"
                    + " VALUES (:c, :a, :d, :amt, :uid)",
                    P.of("c", code, "a", accountNo, "d", day, "amt", amount, "uid", Ctx.user().getUserId()));
        }
    }

    // ------------------------------------------------------------------ edit / delete saved payments

    @GetMapping("/api/payments")
    public Map<String, Object> list(@RequestParam String date, @RequestParam(defaultValue = "area") String mode,
                                    @RequestParam(required = false) String area) {
        LocalDate day = P.parseDate(date, "Date");
        Map<String, Object> p = Ctx.withScope(P.of("date", day));
        StringBuilder where = new StringBuilder();
        if ("area".equals(mode)) {
            if (P.blankToNull(area) == null) {
                throw new ApiException(400, "Please select an area");
            }
            where.append(" AND d.street = :area");
            p.put("area", area.trim());
        }
        List<Map<String, Object>> rows = db.list("SELECT t.* FROM (SELECT p.payment_id, p.debtor_code, d.debtor_name, d.street,"
                + " d.seq, p.account_no, p.day_date, p.amount, dp.account_status"
                + " FROM payments p JOIN debtors d ON d.debtor_code = p.debtor_code AND d.is_active"
                + " LEFT JOIN deposits dp ON dp.debtor_code = p.debtor_code AND dp.account_no = p.account_no"
                + " WHERE p.is_active AND p.day_date = CAST(:date AS date) " + Ctx.scopeSql("d") + where + ") t"
                + orderBy(mode), p);
        return P.of("rows", rows);
    }

    @PutMapping("/api/payments/{id}")
    @Transactional
    public Map<String, Object> edit(@PathVariable int id, @RequestBody Map<String, Object> body) {
        int amount = P.integer(body, "amount", "Amount");
        if (amount < 0) {
            throw new ApiException(400, "Amount cannot be negative");
        }
        Map<String, Object> pay = db.one("SELECT p.payment_id, p.debtor_code, p.account_no, p.day_date, dp.amount_of_advance,"
                + " d.debtor_name FROM payments p JOIN debtors d ON d.debtor_code = p.debtor_code"
                + " JOIN deposits dp ON dp.debtor_code = p.debtor_code AND dp.account_no = p.account_no AND dp.is_active"
                + " WHERE p.is_active AND p.payment_id = :id " + Ctx.scopeSql("d"), Ctx.withScope(P.of("id", id)));
        if (pay == null) {
            throw new ApiException(404, "Payment not found");
        }
        String who = pay.get("debtorName") + " (A/C " + pay.get("accountNo") + ")";
        checkWithinDue(who, (String) pay.get("debtorCode"), ((Number) pay.get("accountNo")).intValue(),
                ((java.sql.Date) pay.get("dayDate")).toLocalDate(), amount, ((Number) pay.get("amountOfAdvance")).longValue());
        db.update("UPDATE payments SET amount = :a, user_id = :u WHERE payment_id = :id",
                P.of("a", amount, "u", Ctx.user().getUserId(), "id", id));
        return P.of("ok", true);
    }

    @DeleteMapping("/api/payments/{id}")
    @Transactional
    public Map<String, Object> delete(@PathVariable int id, @RequestBody Map<String, Object> body) {
        String reason = P.required(body, "reason", "Reason");
        int n = db.update("UPDATE payments p SET is_active = false, delete_reason = :r, deleted_by = :u"
                + " WHERE p.payment_id = :id AND p.is_active AND EXISTS (SELECT 1 FROM debtors d WHERE d.debtor_code = p.debtor_code "
                + Ctx.scopeSql("d") + ")", Ctx.withScope(P.of("r", reason, "u", Ctx.user().getUsername(), "id", id)));
        if (n == 0) {
            throw new ApiException(404, "Payment not found");
        }
        return P.of("ok", true);
    }

    // ------------------------------------------------------------------ account closing

    /** Open accounts that are fully paid and can be closed. */
    @GetMapping("/api/accounts/closable")
    public List<Map<String, Object>> closable() {
        return db.list("SELECT dp.debtor_code, d.debtor_name, dp.account_no, dp.amount_of_advance,"
                + " COALESCE(pa.paid,0) AS paid, dp.amount_of_advance - COALESCE(pa.paid,0) AS due"
                + " FROM deposits dp JOIN debtors d ON d.debtor_code = dp.debtor_code AND d.is_active"
                + " LEFT JOIN (SELECT debtor_code, account_no, SUM(amount) paid FROM payments WHERE is_active"
                + "   GROUP BY debtor_code, account_no) pa ON pa.debtor_code = dp.debtor_code AND pa.account_no = dp.account_no"
                + " WHERE dp.is_active AND dp.account_status = 'OPEN' AND COALESCE(pa.paid,0) >= dp.amount_of_advance "
                + Ctx.scopeSql("d") + " ORDER BY dp.account_no", Ctx.withScope(new HashMap<String, Object>()));
    }

    @PostMapping("/api/accounts/close")
    @Transactional
    public Map<String, Object> close(@RequestBody Map<String, Object> body) {
        String code = P.required(body, "debtorCode", "Debtor");
        int accountNo = P.integer(body, "accountNo", "Account number");
        Map<String, Object> dep = db.one("SELECT dp.amount_of_advance, dp.account_status FROM deposits dp"
                + " JOIN debtors d ON d.debtor_code = dp.debtor_code AND d.is_active"
                + " WHERE dp.is_active AND dp.debtor_code = :c AND dp.account_no = :a " + Ctx.scopeSql("d"),
                Ctx.withScope(P.of("c", code, "a", accountNo)));
        if (dep == null) {
            throw new ApiException(404, "Account not found");
        }
        Long paid = db.scalar("SELECT COALESCE(SUM(amount),0) FROM payments WHERE is_active AND debtor_code = :c AND account_no = :a",
                P.of("c", code, "a", accountNo), Long.class);
        if (paid == null || paid < ((Number) dep.get("amountOfAdvance")).longValue()) {
            throw new ApiException(409, "This account still has an amount due and cannot be closed");
        }
        db.update("UPDATE deposits SET account_status = 'CLOSED' WHERE debtor_code = :c AND account_no = :a AND is_active",
                P.of("c", code, "a", accountNo));
        return P.of("ok", true);
    }
}
