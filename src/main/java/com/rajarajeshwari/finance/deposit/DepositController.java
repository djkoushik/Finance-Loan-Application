package com.rajarajeshwari.finance.deposit;

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

/**
 * Deposits ("advances"): money lent to a debtor and repaid in daily instalments.
 * An account is identified by (debtor code, account number).
 */
@RestController
public class DepositController {

    private static final int DEFAULT_DAYS = 100;

    private final Db db;

    public DepositController(Db db) {
        this.db = db;
    }

    private static final String SELECT =
            "SELECT dp.deposit_id, dp.debtor_code, d.debtor_name, d.phone, d.street, dp.account_no,"
                    + " dp.amount_of_advance, dp.no_of_days, dp.day_of_payment, dp.date_of_advance, dp.closing_date,"
                    + " dp.note, nt.note AS note_text, dp.account_status, dp.account_end_date,"
                    + " COALESCE((SELECT SUM(p.amount) FROM payments p WHERE p.is_active AND p.account_no = dp.account_no"
                    + "   AND p.debtor_code = dp.debtor_code),0) AS paid"
                    + " FROM deposits dp JOIN debtors d ON d.debtor_code = dp.debtor_code AND d.is_active"
                    + " LEFT JOIN note_types nt ON nt.note_id = dp.note"
                    + " WHERE dp.is_active ";

    @GetMapping("/api/deposits")
    public List<Map<String, Object>> list(@RequestParam(required = false) String status,
                                          @RequestParam(required = false) String from,
                                          @RequestParam(required = false) String to,
                                          @RequestParam(required = false) String area,
                                          @RequestParam(required = false) String debtorCode) {
        Map<String, Object> p = Ctx.withScope(new HashMap<String, Object>());
        StringBuilder sql = new StringBuilder(SELECT).append(Ctx.scopeSql("d"));
        String st = status == null || status.trim().isEmpty() ? "OPEN" : status.trim().toUpperCase();
        if ("OPEN".equals(st) || "CLOSED".equals(st)) {
            sql.append(" AND dp.account_status = :st");
            p.put("st", st);
        }
        if (P.blankToNull(from) != null) {
            sql.append(" AND dp.date_of_advance >= :from");
            p.put("from", P.parseDate(from, "From date"));
        }
        if (P.blankToNull(to) != null) {
            sql.append(" AND dp.date_of_advance <= :to");
            p.put("to", P.parseDate(to, "To date"));
        }
        if (P.blankToNull(area) != null) {
            sql.append(" AND d.street = :area");
            p.put("area", area.trim());
        }
        if (P.blankToNull(debtorCode) != null) {
            sql.append(" AND dp.debtor_code = :dc");
            p.put("dc", debtorCode.trim());
        }
        sql.append(" ORDER BY dp.account_no");
        return db.list(sql.toString(), p);
    }

    @GetMapping("/api/deposits/next-account-no")
    public Map<String, Object> nextAccountNo() {
        return P.of("accountNo", nextAccountNumber());
    }

    @GetMapping("/api/deposits/{id}")
    public Map<String, Object> get(@PathVariable int id) {
        return find(id);
    }

    @GetMapping("/api/debtors/{code}/accounts")
    public List<Map<String, Object>> accountsOfDebtor(@PathVariable String code,
                                                      @RequestParam(required = false) String status) {
        Map<String, Object> p = Ctx.withScope(P.of("dc", code));
        String sql = SELECT + Ctx.scopeSql("d") + " AND dp.debtor_code = :dc";
        if (status != null && !status.trim().isEmpty() && !"ALL".equalsIgnoreCase(status)) {
            sql += " AND dp.account_status = :st";
            p.put("st", status.trim().toUpperCase());
        }
        return db.list(sql + " ORDER BY dp.account_no", p);
    }

    @PostMapping("/api/deposits")
    @Transactional
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        String debtorCode = P.required(body, "debtorCode", "Debtor");
        Map<String, Object> debtor = db.one("SELECT debtor_code, user_id FROM debtors d WHERE d.is_active AND d.debtor_code = :c "
                + Ctx.scopeSql("d"), Ctx.withScope(P.of("c", debtorCode)));
        if (debtor == null) {
            throw new ApiException(404, "Debtor not found");
        }
        Fields f = read(body);
        db.lockNumbering();
        if (db.count("SELECT count(*) FROM deposits WHERE is_active AND debtor_code = :c AND account_status = 'OPEN'",
                P.of("c", debtorCode)) > 0) {
            throw new ApiException(409, "This debtor already has a running account. Please close it first.");
        }
        int id = db.scalar("SELECT COALESCE(MAX(deposit_id),0)+1 FROM deposits", P.of(), Integer.class);
        int accountNo = nextAccountNumber();
        Map<String, Object> p = f.params();
        p.put("id", id);
        p.put("acc", accountNo);
        p.put("dc", debtorCode);
        p.put("uid", Ctx.user().getUserId());
        db.update("INSERT INTO deposits(deposit_id, debtor_code, account_no, amount_of_advance, no_of_days, day_of_payment,"
                + " date_of_advance, closing_date, note, account_status, user_id)"
                + " VALUES (:id, :dc, :acc, :amount, :days, :daily, :adv, :closing, :note, 'OPEN', :uid)", p);
        return find(id);
    }

    @PutMapping("/api/deposits/{id}")
    @Transactional
    public Map<String, Object> update(@PathVariable int id, @RequestBody Map<String, Object> body) {
        Map<String, Object> current = find(id);
        Fields f = read(body);
        long paid = ((Number) current.get("paid")).longValue();
        if (f.amount < paid) {
            throw new ApiException(400, "Amount of advance cannot be less than the amount already paid (" + paid + ")");
        }
        Map<String, Object> p = f.params();
        p.put("id", id);
        db.update("UPDATE deposits SET amount_of_advance=:amount, no_of_days=:days, day_of_payment=:daily,"
                + " date_of_advance=:adv, closing_date=:closing, note=:note WHERE deposit_id=:id AND is_active", p);
        return find(id);
    }

    @DeleteMapping("/api/deposits/{id}")
    @Transactional
    public Map<String, Object> delete(@PathVariable int id, @RequestBody Map<String, Object> body) {
        Map<String, Object> current = find(id);
        String reason = P.required(body, "reason", "Reason");
        long payments = db.count("SELECT count(*) FROM payments WHERE is_active AND debtor_code = :c AND account_no = :a",
                P.of("c", current.get("debtorCode"), "a", current.get("accountNo")));
        if (payments > 0) {
            throw new ApiException(409, "This account already has " + payments
                    + " payment(s) and cannot be deleted. Delete those payments first.");
        }
        db.update("UPDATE deposits SET is_active = false, delete_reason = :r, deleted_by = :u WHERE deposit_id = :id",
                P.of("r", reason, "u", Ctx.user().getUsername(), "id", id));
        return P.of("ok", true);
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> find(int id) {
        Map<String, Object> row = db.one(SELECT + Ctx.scopeSql("d") + " AND dp.deposit_id = :id",
                Ctx.withScope(P.of("id", id)));
        if (row == null) {
            throw new ApiException(404, "Deposit not found");
        }
        return row;
    }

    private int nextAccountNumber() {
        Integer n = db.scalar("SELECT COALESCE(MAX(account_no),0)+1 FROM deposits", P.of(), Integer.class);
        return n == null ? 1 : n;
    }

    private Fields read(Map<String, Object> b) {
        Fields f = new Fields();
        f.amount = P.integer(b, "amountOfAdvance", "Amount of advance");
        if (f.amount <= 0) {
            throw new ApiException(400, "Amount of advance must be greater than zero");
        }
        f.days = P.integerOr(b, "noOfDays", DEFAULT_DAYS);
        if (f.days <= 0 || f.days > 1000) {
            throw new ApiException(400, "Number of days must be between 1 and 1000");
        }
        f.daily = P.integer(b, "dayOfPayment", "Day of payment (daily amount)");
        if (f.daily <= 0) {
            throw new ApiException(400, "Day of payment must be greater than zero");
        }
        f.adv = P.date(b, "dateOfAdvance", "Date of advance");
        f.note = P.integer(b, "note", "Note");
        if (db.count("SELECT count(*) FROM note_types WHERE note_id = :n", P.of("n", f.note)) == 0) {
            throw new ApiException(400, "Please choose a valid note");
        }
        return f;
    }

    private static final class Fields {
        int amount;
        int days;
        int daily;
        LocalDate adv;
        int note;

        Map<String, Object> params() {
            Map<String, Object> p = new HashMap<String, Object>();
            p.put("amount", amount);
            p.put("days", days);
            p.put("daily", daily);
            p.put("adv", adv);
            p.put("closing", adv.plusDays(days));
            p.put("note", note);
            return p;
        }
    }
}
