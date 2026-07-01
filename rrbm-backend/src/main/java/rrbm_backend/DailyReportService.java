package rrbm_backend;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class DailyReportService {

    private final DailyReportRepository  reportRepo;
    private final ActivityLogService     activityLogService;
    private final TransactionService     transactionService;
    private final EntityManager          em;
    private final OrderRepository        orderRepository;
    private final UserRepository         userRepository;
    private final ExpenseRepository      expenseRepository;
    private final CashLedgerService      cashLedgerService;
    private final DailyExpenseLogService  dailyExpenseLogService;
    private final BCryptPasswordEncoder  passwordEncoder = new BCryptPasswordEncoder();

    public DailyReportService(DailyReportRepository reportRepo,
                               ActivityLogService activityLogService,
                               TransactionService transactionService,
                               EntityManager em,
                               OrderRepository orderRepository,
                               UserRepository userRepository,
                               ExpenseRepository expenseRepository,
                               CashLedgerService cashLedgerService,
                               DailyExpenseLogService dailyExpenseLogService) {
        this.reportRepo         = reportRepo;
        this.activityLogService = activityLogService;
        this.transactionService = transactionService;
        this.em                 = em;
        this.orderRepository    = orderRepository;
        this.userRepository     = userRepository;
        this.expenseRepository  = expenseRepository;
        this.cashLedgerService  = cashLedgerService;
        this.dailyExpenseLogService = dailyExpenseLogService;
    }

    public Optional<DailyReport> getReportByDate(LocalDate date) {
        return reportRepo.findByReportDate(date);
    }

    public List<DailyReport> getReportsBetween(LocalDate start, LocalDate end) {
        return reportRepo.findByReportDateBetweenOrderByReportDateDesc(start, end);
    }

    /**
     * Normal close: blocks if any ACTIVE/PENDING orders remain.
     */
    @Transactional
    public DailyReport closeDailySales(Long userId, String userName, LocalDate date) {
        return closeDailySales(userId, userName, date, false, null, null);
    }

    /**
     * Force-close override: requires both the closing admin's personal security key
     * AND any Super Admin's security key.  Uncollected orders are moved to
     * PENDING_COLLECTION; their SALE transactions are reversed so they don't
     * appear in today's totals.  Revenue is retroactively posted when collected.
     */
    @Transactional
    public DailyReport closeDailySales(Long userId, String userName, LocalDate date,
                                       boolean forceClose,
                                       String adminSecurityKey,
                                       String superAdminSecurityKey) {
        // Guard: already closed?
        Optional<DailyReport> existing = reportRepo.findByReportDate(date);
        if (existing.isPresent()) {
            throw new RuntimeException("Daily sales for " + date + " have already been closed.");
        }

        // Build the date prefix shared by all order queries for this day
        String datePrefix = String.format("%02d%02d%02d",
                date.getDayOfMonth(), date.getMonthValue(), date.getYear() % 100);

        // Guard: block close if ACTIVE or PENDING orders still exist for this day
        @SuppressWarnings("unchecked")
        List<String> openOrderIds = em.createNativeQuery(
            "SELECT id FROM orders WHERE id LIKE :prefix AND status IN ('ACTIVE','PENDING')")
            .setParameter("prefix", datePrefix + "-%")
            .getResultList();

        int unfulfilledCount = 0;
        BigDecimal unfulfilledAmount = BigDecimal.ZERO;

        if (!openOrderIds.isEmpty()) {
            if (!forceClose) {
                // Calculate unfulfilled totals for the error response
                BigDecimal total = (BigDecimal) em.createNativeQuery(
                    "SELECT COALESCE(SUM(total), 0) FROM orders WHERE id LIKE :prefix AND status IN ('ACTIVE','PENDING')")
                    .setParameter("prefix", datePrefix + "-%")
                    .getSingleResult();
                throw new OpenOrdersException(openOrderIds.size(), total);
            }

            // ── Force-close: verify the logged-in admin's personal security key ──
            // Use orElseThrow so an unrecognised userId is a hard rejection rather than
            // a silent no-op (ifPresent would skip validation if the user is not found).
            User caller = userRepository.findById(userId)
                    .orElseThrow(() -> new RuntimeException("User not found — cannot verify admin security key"));
            if (caller.getAdminSecurityKey() == null
                    || !passwordEncoder.matches(adminSecurityKey, caller.getAdminSecurityKey())) {
                throw new RuntimeException("Admin security key is incorrect");
            }

            // Validate super admin security key (dual-auth for force-close)
            if (superAdminSecurityKey == null || superAdminSecurityKey.isBlank()) {
                throw new RuntimeException("Super admin security key is required for force-close");
            }
            List<User> superAdmins = userRepository.findByRole("SUPER_ADMIN");
            if (superAdmins.isEmpty()) {
                throw new RuntimeException("No super admin accounts exist — cannot authorize force-close");
            }
            boolean superAdminMatched = superAdmins.stream()
                    .anyMatch(sa -> sa.getAdminSecurityKey() != null
                            && passwordEncoder.matches(superAdminSecurityKey, sa.getAdminSecurityKey()));
            if (!superAdminMatched) {
                throw new RuntimeException("Super admin security key is incorrect");
            }

            // Defer uncollected orders: mark PENDING_COLLECTION + reverse their SALE transactions
            List<Order> uncollectedOrders = orderRepository.findAllById(openOrderIds);
            for (Order o : uncollectedOrders) {
                o.setStatus("PENDING_COLLECTION");
                o.setPendingCollectionAt(OffsetDateTime.now());
                orderRepository.save(o);
                // Reverse the SALE transaction so it doesn't appear in today's totals
                transactionService.recordDeferralVoid(o, userId);
                unfulfilledAmount = unfulfilledAmount.add(o.getTotal() != null ? o.getTotal() : BigDecimal.ZERO);
            }
            unfulfilledCount = uncollectedOrders.size();
        }

        // ── Operational stats (from orders) ────────────────────────────
        // Used for order-count breakdown and source analytics.
        // These do NOT drive financial totals anymore; transactions do.

        @SuppressWarnings("unchecked")
        List<Object[]> orderStats = em.createNativeQuery(
            "SELECT " +
            "  COUNT(*) FILTER (WHERE status NOT IN ('CANCELLED','PENDING','PENDING_COLLECTION')) AS active_count, " +
            "  COUNT(*) FILTER (WHERE status = 'CANCELLED')                  AS cancelled_count, " +
            "  COUNT(*) FILTER (WHERE source = 'WALK_IN'      AND status NOT IN ('CANCELLED','PENDING','PENDING_COLLECTION')) AS walk_in, " +
            "  COUNT(*) FILTER (WHERE source = 'AGENT'        AND status NOT IN ('CANCELLED','PENDING','PENDING_COLLECTION')) AS agent, " +
            "  COUNT(*) FILTER (WHERE source = 'ECOMMERCE'    AND status NOT IN ('CANCELLED','PENDING','PENDING_COLLECTION')) AS ecommerce, " +
            "  COUNT(*) FILTER (WHERE source = 'FACEBOOK_PAGE' AND status NOT IN ('CANCELLED','PENDING','PENDING_COLLECTION')) AS fb_page " +
            "FROM orders WHERE id LIKE :prefix"
        ).setParameter("prefix", datePrefix + "-%").getResultList();

        Object itemsSoldResult = em.createNativeQuery(
            "SELECT COALESCE(SUM(oi.quantity), 0) FROM order_items oi " +
            "JOIN orders o ON oi.order_id = o.id " +
            "WHERE o.id LIKE :prefix AND o.status NOT IN ('CANCELLED','PENDING','PENDING_COLLECTION')"
        ).setParameter("prefix", datePrefix + "-%").getSingleResult();

        // Total pizza boxes sold (V75) — quantity for products in the "Pizza Box" category
        Object pizzaBoxesResult = em.createNativeQuery(
            "SELECT COALESCE(SUM(oi.quantity), 0) FROM order_items oi " +
            "JOIN orders o ON oi.order_id = o.id " +
            "JOIN products p ON oi.product_id = p.id " +
            "WHERE o.id LIKE :prefix AND o.status NOT IN ('CANCELLED','PENDING','PENDING_COLLECTION') " +
            "AND p.category = 'Pizza Box'"
        ).setParameter("prefix", datePrefix + "-%").getSingleResult();

        @SuppressWarnings("unchecked")
        List<Object[]> topProductResult = em.createNativeQuery(
            "SELECT oi.product_name, SUM(oi.quantity) AS total_qty FROM order_items oi " +
            "JOIN orders o ON oi.order_id = o.id " +
            "WHERE o.id LIKE :prefix AND o.status NOT IN ('CANCELLED','PENDING','PENDING_COLLECTION') " +
            "GROUP BY oi.product_name ORDER BY total_qty DESC LIMIT 1"
        ).setParameter("prefix", datePrefix + "-%").getResultList();

        Object[] stats = orderStats.isEmpty()
            ? new Object[]{0L, 0L, 0L, 0L, 0L, 0L}
            : orderStats.get(0);

        // ── Financial stats (from transaction ledger) ──────────────────
        // grossSales      = SUM(SALE transactions for date)
        // refundsTotal    = SUM(REFUND + VOID + RETURN)  — already negative
        // adjustmentsTotal= SUM(ADJUSTMENT)
        // netSales        = grossSales + refundsTotal + adjustmentsTotal
        BigDecimal grossSales        = transactionService.getGrossSalesForDate(date);
        BigDecimal refundsTotal      = transactionService.getRefundsTotalForDate(date);
        BigDecimal adjustmentsTotal  = transactionService.getAdjustmentsTotalForDate(date);
        BigDecimal netSales          = grossSales.add(refundsTotal).add(adjustmentsTotal);
        long       totalTransactions = transactionService.countTransactionsForDate(date);

        // ── Build the immutable snapshot ───────────────────────────────
        DailyReport report = new DailyReport();
        report.setReportDate(date);

        // Operational
        report.setTotalOrders(((Number) stats[0]).intValue());
        report.setTotalCancelled(((Number) stats[1]).intValue());
        report.setWalkInCount(((Number) stats[2]).intValue());
        report.setAgentCount(((Number) stats[3]).intValue());
        report.setEcommerceCount(((Number) stats[4]).intValue());
        report.setFbPageCount(((Number) stats[5]).intValue());
        report.setTotalItemsSold(((Number) itemsSoldResult).intValue());
        report.setTotalPizzaBoxes(((Number) pizzaBoxesResult).intValue());

        if (!topProductResult.isEmpty()) {
            report.setTopProduct((String) topProductResult.get(0)[0]);
            report.setTopProductQty(((Number) topProductResult.get(0)[1]).intValue());
        }

        // Financial (transaction-ledger driven)
        report.setGrossSales(grossSales);
        report.setRefundsTotal(refundsTotal);
        report.setAdjustmentsTotal(adjustmentsTotal);
        report.setNetSales(netSales);
        report.setTotalTransactions((int) totalTransactions);

        // Legacy field kept for backward-compat; equals netSales going forward
        report.setTotalRevenue(netSales);
        report.setCancelledAmount(BigDecimal.ZERO); // no longer summed — use VOID transactions

        // Force-close unfulfilled stats
        report.setUnfulfilledOrders(unfulfilledCount);
        report.setUnfulfilledAmount(unfulfilledAmount);

        // ── Expense totals (V61) ───────────────────────────────────────
        BigDecimal totalExpenses = expenseRepository.sumNonVoidedForDate(date);
        long expensesCount = expenseRepository.countNonVoidedForDate(date);
        report.setTotalExpenses(totalExpenses != null ? totalExpenses : BigDecimal.ZERO);
        report.setExpensesCount((int) expensesCount);

        // ── Cash on hand snapshot (V80) ───────────────────────────────
        // Freeze the cash-on-hand balance AS OF this report's date (rows dated on
        // or before `date`), not the live "now" balance. This keeps the snapshot
        // correct for backdated / late / out-of-order / import closes, and makes
        // it genuinely unaffected by later days. Cash on hand persists in cash_ledger.
        report.setCashOnHand(cashLedgerService.getCashOnHandAsOf(date));

        report.setClosedBy(userId);
        report.setClosedAt(OffsetDateTime.now());
        report.setCreatedAt(OffsetDateTime.now()); // N-2: was never set; created_at always NULL

        reportRepo.save(report);

        // Persist the per-day expense snapshot alongside the report (Expense Log tab).
        dailyExpenseLogService.snapshotForDate(date, userId);

        // Close activity-log entries for that date
        activityLogService.closeLogsForDate(date);

        // Log the close action
        String closeNote = "Closed daily sales for " + date
                + " — Net: ₱" + netSales
                + " | Gross: ₱" + grossSales
                + " | Refunds: ₱" + refundsTotal;
        if (unfulfilledCount > 0) {
            closeNote += " | FORCED — " + unfulfilledCount + " order(s) deferred to collection (₱" + unfulfilledAmount + ")";
        }
        activityLogService.log(userId, userName, "CLOSE_DAILY_SALES", closeNote,
                "DAILY_REPORT", report.getId().toString());

        return report;
    }

    /**
     * Auto-closes a daily report for a past date after a batch import.
     *
     * Differences from closeDailySales():
     *   - Idempotent: if a report already exists for the date, returns silently (no exception).
     *   - Does NOT enforce the ACTIVE/PENDING guard — batch-imported ACTIVE orders are
     *     confirmed sales; PENDING (COD) orders are counted as unfulfilled but are NOT
     *     moved to PENDING_COLLECTION and their transactions are NOT reversed.
     *   - No security-key validation required — this is an internal post-import operation.
     *
     * Financial figures come from the transaction ledger (via transactionService) which
     * is correct because createOrderAtDate() uses recordSale(order, userId, targetDate)
     * to stamp effectiveDate = the order's date, not today.
     *
     * Called only by ImportController after a successful batch commit.
     */
    @Transactional
    public boolean closeForImportDate(Long userId, String userName, LocalDate date) {
        // Idempotent: skip silently if already closed
        if (reportRepo.findByReportDate(date).isPresent()) return false;

        DailyReport report = new DailyReport();
        populateSnapshot(report, date);
        report.setClosedBy(userId);
        report.setClosedAt(OffsetDateTime.now());
        report.setCreatedAt(OffsetDateTime.now());
        reportRepo.save(report);

        // Persist the per-day expense snapshot alongside the report (Expense Log tab).
        dailyExpenseLogService.snapshotForDate(date, userId);

        activityLogService.log(userId, userName, "CLOSE_DAILY_SALES",
                "Auto-closed daily report for " + date + " via batch import"
                + " — Net: ₱" + report.getNetSales() + " | Gross: ₱" + report.getGrossSales()
                + " | Expenses: ₱" + report.getTotalExpenses(),
                "DAILY_REPORT", report.getId().toString());
        return true;
    }

    /**
     * Recompute an <b>already-closed</b> daily report from the ledger and stamp it "amended" (V85).
     *
     * <p>Used after a backdated "Add Records" commit so late records actually appear in a day that
     * was already snapshotted. Overwrites the snapshot fields via {@link #populateSnapshot} but
     * <b>preserves</b> the original {@code closedBy}/{@code closedAt}/{@code createdAt}, and sets
     * {@code amended=true} / {@code amendedAt=now} / {@code amendedBy=userId}.
     *
     * <p>Idempotent and safe: if no report exists for {@code date} (the day was never closed, so it
     * computes live), this is a no-op. Returns {@code true} iff a report was recomputed.
     */
    @Transactional
    public boolean recomputeForDate(Long userId, String userName, LocalDate date) {
        Optional<DailyReport> existing = reportRepo.findByReportDate(date);
        if (existing.isEmpty()) return false;   // never closed → nothing frozen to refresh

        DailyReport report = existing.get();
        populateSnapshot(report, date);          // overwrite snapshot; createdAt/closedBy/closedAt untouched
        report.setAmended(true);
        report.setAmendedAt(OffsetDateTime.now());
        report.setAmendedBy(userId);
        reportRepo.save(report);

        // Keep the per-day expense snapshot in sync with the amended report.
        dailyExpenseLogService.snapshotForDate(date, userId);

        activityLogService.log(userId, userName, "AMEND_DAILY_REPORT",
                "Recomputed daily report for " + date + " after backdated entry"
                + " — Net: ₱" + report.getNetSales() + " | Gross: ₱" + report.getGrossSales()
                + " | Expenses: ₱" + report.getTotalExpenses(),
                "DAILY_REPORT", report.getId().toString());
        return true;
    }

    /**
     * Recompute the closed daily reports affected by a backdated commit and return the dates amended.
     *
     * <p>Two effects are covered:
     * <ul>
     *   <li><b>Own-date refresh:</b> every {@code affectedDate} whose report is already closed —
     *       its sales / expense / unfulfilled totals changed.</li>
     *   <li><b>Cash cascade:</b> {@code cashOnHand} is a running as-of balance, so a cash-affecting
     *       (non-recording-only) entry backdated before today shifts the frozen cash snapshot of
     *       every <i>later</i> closed day too. So additionally recompute all closed reports from
     *       {@code earliestCashAffectingDate} through today. Recording-only entries move no cash, so
     *       they only need their own date.</li>
     * </ul>
     *
     * @param earliestCashAffectingDate earliest date carrying a non-recording-only entry, or
     *                                  {@code null} if the whole commit was recording-only
     */
    @Transactional
    public List<LocalDate> recomputeAffected(Long userId, String userName,
                                             java.util.Collection<LocalDate> affectedDates,
                                             LocalDate earliestCashAffectingDate) {
        java.util.TreeSet<LocalDate> targets = new java.util.TreeSet<>();

        // Own-date refresh (covers recording-only sales/expense total changes too).
        for (LocalDate d : affectedDates) {
            if (reportRepo.findByReportDate(d).isPresent()) targets.add(d);
        }

        // Cash cascade: from the earliest cash-affecting date through today.
        if (earliestCashAffectingDate != null) {
            LocalDate today = LocalDate.now();
            if (!earliestCashAffectingDate.isAfter(today)) {
                reportRepo.findByReportDateBetweenOrderByReportDateDesc(earliestCashAffectingDate, today)
                        .forEach(r -> targets.add(r.getReportDate()));
            }
        }

        List<LocalDate> amended = new java.util.ArrayList<>();
        for (LocalDate d : targets) {
            if (recomputeForDate(userId, userName, d)) amended.add(d);
        }
        return amended;
    }

    /**
     * Populate a {@link DailyReport}'s snapshot fields from the ledger + operational queries for
     * {@code date}. Shared by {@link #closeForImportDate} (new report) and {@link #recomputeForDate}
     * (existing report). Sets only snapshot data — never closedBy/closedAt/createdAt/amended, so each
     * caller controls the audit/close metadata. {@code closeDailySales} is intentionally left as-is.
     */
    private void populateSnapshot(DailyReport report, LocalDate date) {
        String datePrefix = String.format("%02d%02d%02d",
                date.getDayOfMonth(), date.getMonthValue(), date.getYear() % 100);

        // ── Operational stats (same queries as closeDailySales) ───────────────
        @SuppressWarnings("unchecked")
        List<Object[]> orderStats = em.createNativeQuery(
            "SELECT " +
            "  COUNT(*) FILTER (WHERE status NOT IN ('CANCELLED','PENDING','PENDING_COLLECTION')) AS active_count, " +
            "  COUNT(*) FILTER (WHERE status = 'CANCELLED')                  AS cancelled_count, " +
            "  COUNT(*) FILTER (WHERE source = 'WALK_IN'      AND status NOT IN ('CANCELLED','PENDING','PENDING_COLLECTION')) AS walk_in, " +
            "  COUNT(*) FILTER (WHERE source = 'AGENT'        AND status NOT IN ('CANCELLED','PENDING','PENDING_COLLECTION')) AS agent, " +
            "  COUNT(*) FILTER (WHERE source = 'ECOMMERCE'    AND status NOT IN ('CANCELLED','PENDING','PENDING_COLLECTION')) AS ecommerce, " +
            "  COUNT(*) FILTER (WHERE source = 'FACEBOOK_PAGE' AND status NOT IN ('CANCELLED','PENDING','PENDING_COLLECTION')) AS fb_page " +
            "FROM orders WHERE id LIKE :prefix"
        ).setParameter("prefix", datePrefix + "-%").getResultList();

        Object itemsSoldResult = em.createNativeQuery(
            "SELECT COALESCE(SUM(oi.quantity), 0) FROM order_items oi " +
            "JOIN orders o ON oi.order_id = o.id " +
            "WHERE o.id LIKE :prefix AND o.status NOT IN ('CANCELLED','PENDING','PENDING_COLLECTION')"
        ).setParameter("prefix", datePrefix + "-%").getSingleResult();

        // Total pizza boxes sold (V75) — quantity for products in the "Pizza Box" category
        Object pizzaBoxesResult = em.createNativeQuery(
            "SELECT COALESCE(SUM(oi.quantity), 0) FROM order_items oi " +
            "JOIN orders o ON oi.order_id = o.id " +
            "JOIN products p ON oi.product_id = p.id " +
            "WHERE o.id LIKE :prefix AND o.status NOT IN ('CANCELLED','PENDING','PENDING_COLLECTION') " +
            "AND p.category = 'Pizza Box'"
        ).setParameter("prefix", datePrefix + "-%").getSingleResult();

        @SuppressWarnings("unchecked")
        List<Object[]> topProductResult = em.createNativeQuery(
            "SELECT oi.product_name, SUM(oi.quantity) AS total_qty FROM order_items oi " +
            "JOIN orders o ON oi.order_id = o.id " +
            "WHERE o.id LIKE :prefix AND o.status NOT IN ('CANCELLED','PENDING','PENDING_COLLECTION') " +
            "GROUP BY oi.product_name ORDER BY total_qty DESC LIMIT 1"
        ).setParameter("prefix", datePrefix + "-%").getResultList();

        Object[] stats = orderStats.isEmpty()
            ? new Object[]{0L, 0L, 0L, 0L, 0L, 0L}
            : orderStats.get(0);

        // ── Unfulfilled (PENDING/COD) — counted but NOT moved ────────────────
        Object unfulfilledTotalResult = em.createNativeQuery(
            "SELECT COALESCE(SUM(total), 0) FROM orders WHERE id LIKE :prefix AND status = 'PENDING'")
            .setParameter("prefix", datePrefix + "-%").getSingleResult();
        Object unfulfilledCountResult = em.createNativeQuery(
            "SELECT COUNT(*) FROM orders WHERE id LIKE :prefix AND status = 'PENDING'")
            .setParameter("prefix", datePrefix + "-%").getSingleResult();

        // ── Financial stats from transaction ledger ───────────────────────────
        // Correct because createOrderAtDate() stamps effectiveDate = targetDate via
        // the recordSale(order, userId, effectiveDate) overload.
        BigDecimal grossSales       = transactionService.getGrossSalesForDate(date);
        BigDecimal refundsTotal     = transactionService.getRefundsTotalForDate(date);
        BigDecimal adjustmentsTotal = transactionService.getAdjustmentsTotalForDate(date);
        BigDecimal netSales         = grossSales.add(refundsTotal).add(adjustmentsTotal);
        long       totalTxns        = transactionService.countTransactionsForDate(date);

        // ── Build snapshot (overwrites the passed report's snapshot fields) ────
        report.setReportDate(date);
        report.setTotalOrders(((Number) stats[0]).intValue());
        report.setTotalCancelled(((Number) stats[1]).intValue());
        report.setWalkInCount(((Number) stats[2]).intValue());
        report.setAgentCount(((Number) stats[3]).intValue());
        report.setEcommerceCount(((Number) stats[4]).intValue());
        report.setFbPageCount(((Number) stats[5]).intValue());
        report.setTotalItemsSold(((Number) itemsSoldResult).intValue());
        report.setTotalPizzaBoxes(((Number) pizzaBoxesResult).intValue());

        if (!topProductResult.isEmpty()) {
            report.setTopProduct((String) topProductResult.get(0)[0]);
            report.setTopProductQty(((Number) topProductResult.get(0)[1]).intValue());
        }

        report.setGrossSales(grossSales);
        report.setRefundsTotal(refundsTotal);
        report.setAdjustmentsTotal(adjustmentsTotal);
        report.setNetSales(netSales);
        report.setTotalRevenue(netSales);
        report.setTotalTransactions((int) totalTxns);
        report.setCancelledAmount(BigDecimal.ZERO);
        report.setUnfulfilledOrders(((Number) unfulfilledCountResult).intValue());
        report.setUnfulfilledAmount(new java.math.BigDecimal(unfulfilledTotalResult.toString()));

        // ── Expense totals (V61) ───────────────────────────────────────
        BigDecimal totalExpenses = expenseRepository.sumNonVoidedForDate(date);
        long expensesCount = expenseRepository.countNonVoidedForDate(date);
        report.setTotalExpenses(totalExpenses != null ? totalExpenses : BigDecimal.ZERO);
        report.setExpensesCount((int) expensesCount);

        // ── Cash on hand snapshot (V80) ───────────────────────────────
        // Freeze the cash-on-hand balance AS OF this report's date (rows dated on
        // or before `date`), not the live "now" balance. This keeps the snapshot
        // correct for backdated / late / out-of-order / import closes, and makes
        // it genuinely unaffected by later days. Cash on hand persists in cash_ledger.
        report.setCashOnHand(cashLedgerService.getCashOnHandAsOf(date));
    }
}
