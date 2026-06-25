package rrbm_backend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Single write choke point for the cash-on-hand ledger. Every cash movement in
 * the system funnels through here so the {@code cash_ledger} table stays the one
 * source of truth. Cash on hand = SUM(amount) over all rows.
 *
 * Automatic movements (cash orders, cash expenses) are idempotent and reversible:
 *   - orders paid in cash add a +CASH_SALE row; cancel/return/void writes a
 *     negative offset (never deletes), capped at the remaining net so we never
 *     over-reverse.
 *   - cash expenses are reconciled to a target (-total when CASH & not voided,
 *     else 0) so create / edit / payment-method switch / void all stay correct
 *     by writing only the delta.
 */
@Service
public class CashLedgerService {

    private static final Logger log = LoggerFactory.getLogger(CashLedgerService.class);

    static final String T_OPENING    = "OPENING_BALANCE";
    static final String T_ADD_CASH   = "ADD_CASH";
    static final String T_CASH_SALE  = "CASH_SALE";
    static final String T_CASH_EXP   = "CASH_EXPENSE";
    static final String T_DEPOSIT    = "DEPOSIT";
    static final String T_ADJUSTMENT = "ADJUSTMENT";

    private static final String REF_ORDER   = "ORDER";
    private static final String REF_EXPENSE  = "EXPENSE";
    private static final String REF_MANUAL   = "MANUAL";

    private final CashLedgerRepository repo;
    private final ActivityLogService   activityLogService;

    public CashLedgerService(CashLedgerRepository repo, ActivityLogService activityLogService) {
        this.repo = repo;
        this.activityLogService = activityLogService;
    }

    // ── Reads ───────────────────────────────────────────────────────────────

    public BigDecimal getCashOnHand() {
        BigDecimal v = repo.getCashOnHand();
        return v != null ? v : BigDecimal.ZERO;
    }

    /** Cash on hand as of the end of the given business day (rows dated on or
     *  before that day). The daily-report close uses this so the snapshot
     *  reflects that specific day, not the live balance at the moment of close.
     *  Falls back to the live balance if date is null. */
    public BigDecimal getCashOnHandAsOf(LocalDate date) {
        if (date == null) return getCashOnHand();
        BigDecimal v = repo.getCashOnHandAsOf(date);
        return v != null ? v : BigDecimal.ZERO;
    }

    public boolean ledgerIsEmpty() {
        return repo.count() == 0;
    }

    public List<CashLedgerEntry> history(int limit, int offset) {
        int page = offset > 0 ? offset / Math.max(limit, 1) : 0;
        return repo.findAllByOrderByCreatedAtDescIdDesc(PageRequest.of(page, Math.max(limit, 1)));
    }

    // ── Automatic: orders ─────────────────────────────────────────────────────

    /**
     * Record a cash inflow for an order paid in cash. Idempotent: a second call
     * for the same order is a no-op (guards against re-saves / retries).
     */
    @Transactional
    public void recordOrderCashSale(Order order, Long userId, String userName, LocalDate date) {
        if (order == null || order.getTotal() == null) return;
        if (repo.existsByEntryTypeAndReferenceTypeAndReferenceId(T_CASH_SALE, REF_ORDER, order.getId())) {
            return;
        }
        write(T_CASH_SALE, order.getTotal(), date, REF_ORDER, order.getId(),
                "Cash order " + order.getId() + " — " + order.getCustomerName(),
                userId, userName, "CASH_SALE");
    }

    /** Fully reverse the remaining cash recorded for an order (cancellation). */
    @Transactional
    public void reverseOrderCashSale(String orderId, Long userId, String userName, String reason) {
        BigDecimal net = repo.sumForReference(T_CASH_SALE, REF_ORDER, orderId);
        reverseOrderCashPartial(orderId, net, userId, userName, reason);
    }

    /**
     * Reverse part of an order's cash inflow (partial return / item void).
     * No-op when the order had no cash inflow or is already fully reversed;
     * capped at the remaining net so cash on hand can't be driven below the
     * actual cash that came in for this order.
     */
    @Transactional
    public void reverseOrderCashPartial(String orderId, BigDecimal amount,
                                        Long userId, String userName, String reason) {
        if (amount == null || amount.signum() <= 0) return;
        BigDecimal remaining = repo.sumForReference(T_CASH_SALE, REF_ORDER, orderId);
        if (remaining.signum() <= 0) return;                 // not a cash order / already reversed
        BigDecimal rev = amount.min(remaining);
        write(T_CASH_SALE, rev.negate(), LocalDate.now(), REF_ORDER, orderId,
                "Reversal: order " + orderId + " — " + reason,
                userId, userName, "CASH_SALE_REVERSAL");
    }

    // ── Automatic: expenses ────────────────────────────────────────────────────

    /**
     * Reconcile an expense's cash impact to where it should be.
     * Target = -total when the expense is CASH and not voided, else 0.
     * Writes only the delta, so create, edit (amount or payment-method change),
     * and void all keep cash on hand exactly right.
     */
    @Transactional
    public void reconcileExpenseCash(Expense expense, Long userId, String userName) {
        if (expense == null) return;
        boolean isCash = "CASH".equalsIgnoreCase(
                expense.getPaymentMethod() == null ? "" : expense.getPaymentMethod().trim());
        BigDecimal total = expense.getTotalAmount() != null ? expense.getTotalAmount() : BigDecimal.ZERO;

        BigDecimal target  = (isCash && !expense.isVoided()) ? total.negate() : BigDecimal.ZERO;
        BigDecimal current = repo.sumForReference(T_CASH_EXP, REF_EXPENSE, String.valueOf(expense.getId()));
        BigDecimal delta   = target.subtract(current);
        if (delta.signum() == 0) return;

        String action = current.signum() == 0 ? "CASH_EXPENSE" : "CASH_EXPENSE_RECONCILE";
        String note   = current.signum() == 0
                ? "Cash expense #" + expense.getId() + " — ₱" + total
                : "Reconcile cash expense #" + expense.getId() + " — now ₱"
                  + (isCash && !expense.isVoided() ? total : BigDecimal.ZERO)
                  + (expense.isVoided() ? " (voided)" : "");
        write(T_CASH_EXP, delta, expense.getDate() != null ? expense.getDate() : LocalDate.now(),
                REF_EXPENSE, String.valueOf(expense.getId()), note, userId, userName, action);
    }

    // ── Manual entries ─────────────────────────────────────────────────────────

    @Transactional
    public CashLedgerEntry recordOpeningBalance(BigDecimal amount, LocalDate date,
                                                String note, Long userId, String userName) {
        return write(T_OPENING, amount, date, REF_MANUAL, null,
                note != null && !note.isBlank() ? note : "Opening balance",
                userId, userName, "CASH_OPENING");
    }

    @Transactional
    public CashLedgerEntry addCash(BigDecimal amount, LocalDate date,
                                   String note, Long userId, String userName) {
        return write(T_ADD_CASH, amount, date, REF_MANUAL, null,
                note != null && !note.isBlank() ? note : "Added cash",
                userId, userName, "CASH_ADD");
    }

    @Transactional
    public CashLedgerEntry deposit(BigDecimal amount, LocalDate date,
                                   String note, Long userId, String userName) {
        // Stored as an outflow regardless of the sign the caller passed.
        BigDecimal out = amount.abs().negate();
        return write(T_DEPOSIT, out, date, REF_MANUAL, null,
                note != null && !note.isBlank() ? note : "Bank deposit",
                userId, userName, "CASH_DEPOSIT");
    }

    @Transactional
    public CashLedgerEntry adjustment(BigDecimal signedAmount, LocalDate date,
                                      String note, Long userId, String userName) {
        return write(T_ADJUSTMENT, signedAmount, date, REF_MANUAL, null,
                note != null && !note.isBlank() ? note : "Cash adjustment",
                userId, userName, "CASH_ADJUST");
    }

    // ── Internal ────────────────────────────────────────────────────────────────

    private CashLedgerEntry write(String type, BigDecimal amount, LocalDate date,
                                  String refType, String refId, String note,
                                  Long userId, String userName, String activityAction) {
        CashLedgerEntry e = new CashLedgerEntry();
        e.setEntryType(type);
        e.setAmount(amount);
        e.setEntryDate(date != null ? date : LocalDate.now());
        e.setReferenceType(refType);
        e.setReferenceId(refId);
        e.setNote(note);
        e.setCreatedBy(userId);
        e.setCreatedByName(userName);
        CashLedgerEntry saved = repo.save(e);

        activityLogService.log(userId, userName, activityAction,
                note + " (₱" + amount + ", cash on hand now ₱" + getCashOnHand() + ")",
                "CASH", String.valueOf(saved.getId()));
        return saved;
    }
}
