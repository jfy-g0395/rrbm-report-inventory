package rrbm_backend;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Core accounting service.
 *
 * Every financial event flows through here and writes one immutable
 * Transaction row.  Nothing in this service modifies orders or reports —
 * it only appends to the ledger.
 *
 * Calculation convention:
 *   NET_SALES = grossSales + refundsTotal + adjustmentsTotal
 *   (refundsTotal and adjustmentsTotal are already negative when they represent reversals)
 */
@Service
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final ActivityLogService    activityLogService;
    private final OrderRepository       orderRepository;
    private final ProductRepository     productRepository;
    private final InventoryService      inventoryService;

    public TransactionService(TransactionRepository transactionRepository,
                              ActivityLogService activityLogService,
                              OrderRepository orderRepository,
                              ProductRepository productRepository,
                              InventoryService inventoryService) {
        this.transactionRepository = transactionRepository;
        this.activityLogService    = activityLogService;
        this.orderRepository       = orderRepository;
        this.productRepository     = productRepository;
        this.inventoryService      = inventoryService;
    }

    // ── Internal helpers (called from OrderService) ────────────────────

    /**
     * Creates a positive SALE transaction when an order is placed.
     * Idempotent: skips silently if a SALE already exists for this order
     * (protects against double-recording during migration backfill).
     */
    @Transactional
    public Transaction recordSale(Order order, Long createdByUserId) {
        if (transactionRepository.existsByOrderIdAndTransactionType(order.getId(), "SALE")) {
            return null; // already recorded by backfill migration — do nothing
        }
        Transaction txn = new Transaction();
        txn.setTransactionCode("SALE-" + order.getId());
        txn.setOrderId(order.getId());
        txn.setTransactionType("SALE");
        txn.setAmount(order.getTotal() != null ? order.getTotal() : BigDecimal.ZERO);
        txn.setReferenceType("ORDER");
        txn.setReferenceId(order.getId());
        txn.setNotes("Sale — " + order.getCustomerName());
        txn.setCreatedBy(createdByUserId);
        txn.setEffectiveDate(LocalDate.now());
        return transactionRepository.save(txn);
    }

    /**
     * Same as recordSale(Order, Long) but with a caller-supplied effectiveDate.
     * Used exclusively by the batch import pipeline so that backdated orders land
     * on the correct date in the transaction ledger — not on today.
     * The existing two-arg overload above is untouched and still uses LocalDate.now().
     */
    @Transactional
    public Transaction recordSale(Order order, Long createdByUserId, LocalDate effectiveDate) {
        if (transactionRepository.existsByOrderIdAndTransactionType(order.getId(), "SALE")) {
            return null;
        }
        Transaction txn = new Transaction();
        txn.setTransactionCode("SALE-" + order.getId());
        txn.setOrderId(order.getId());
        txn.setTransactionType("SALE");
        txn.setAmount(order.getTotal() != null ? order.getTotal() : BigDecimal.ZERO);
        txn.setReferenceType("ORDER");
        txn.setReferenceId(order.getId());
        txn.setNotes("Sale — " + order.getCustomerName());
        txn.setCreatedBy(createdByUserId);
        txn.setEffectiveDate(effectiveDate);
        return transactionRepository.save(txn);
    }

    /**
     * Creates a negative VOID transaction using an explicit net amount.
     *
     * The amount passed must already reflect any prior item-level voids:
     *   effectiveVoid = order.getTotal() - order.getVoidedAmount()
     *
     * effective_date = TODAY (date of cancellation), NOT the original order date.
     * This keeps historical daily reports immutable.
     */
    @Transactional
    public Transaction recordVoid(Order order, BigDecimal amount,
                                  Long cancelledByUserId, String reason) {
        Transaction txn = new Transaction();
        txn.setTransactionCode("VOID-" + order.getId() + "-" + System.currentTimeMillis());
        txn.setOrderId(order.getId());
        txn.setTransactionType("VOID");
        txn.setAmount(amount.negate());
        txn.setReferenceType("ORDER");
        txn.setReferenceId(order.getId());
        txn.setNotes("Void/Cancel — " + (reason != null && !reason.isBlank() ? reason : "Order cancelled"));
        txn.setCreatedBy(cancelledByUserId);
        txn.setEffectiveDate(LocalDate.now());
        return transactionRepository.save(txn);
    }

    /**
     * Records a RETURN ledger entry when a post-sale return includes a refund.
     *
     * Called by OrderService.processReturn() inside the same @Transactional
     * boundary as the inventory adjustments — if this write fails, all stock
     * changes roll back atomically.
     *
     * Uses transaction type RETURN, which is already summed in
     * getRefundsTotalForDate() alongside REFUND and VOID, so return refunds
     * appear correctly in daily report net sales with no other changes needed.
     *
     * Does NOT touch inventory — stock adjustments are handled by
     * InventoryService.processReturnForItem() before this is called.
     *
     * amount must be positive; stored as negative (reversal).
     * effectiveDate = today so historical daily reports are never touched.
     */
    @Transactional
    public Transaction recordReturnRefund(String orderId, BigDecimal amount,
                                          String reason, Long userId, String userName) {
        validatePositiveAmount(amount, "Return refund");

        Transaction txn = new Transaction();
        txn.setTransactionCode("RETURN-" + orderId + "-" + System.currentTimeMillis());
        txn.setOrderId(orderId);
        txn.setTransactionType("RETURN");
        txn.setAmount(amount.negate());
        txn.setReferenceType("ORDER");
        txn.setReferenceId(orderId);
        txn.setNotes(reason != null && !reason.isBlank() ? reason : "Return refund for order " + orderId);
        txn.setCreatedBy(userId);
        txn.setEffectiveDate(LocalDate.now());

        Transaction saved = transactionRepository.save(txn);
        activityLogService.log(userId, userName, "RETURN_ORDER",
                "Return refund of ₱" + amount + " for order " + orderId
                + (reason != null && !reason.isBlank() ? " — " + reason : ""),
                "ORDER", orderId);
        return saved;
    }

    // ── API-facing methods (called from TransactionController) ─────────

    /**
     * Records a VOID ledger entry for a partial or full item-level void.
     * Called by OrderService.voidOrderItems() after quantities are validated.
     *
     * amount — the monetary value being removed (sum of voidedQty × unitPrice
     *          across all voided items); must be positive, stored as negative.
     * effectiveDate = today so historical daily reports are never touched.
     */
    @Transactional
    public Transaction recordItemVoid(String orderId, BigDecimal amount,
                                      String reason, Long userId, String userName) {
        validatePositiveAmount(amount, "Void");

        Transaction txn = new Transaction();
        txn.setTransactionCode("VOID-" + orderId + "-" + System.currentTimeMillis());
        txn.setOrderId(orderId);
        txn.setTransactionType("VOID");
        txn.setAmount(amount.negate());
        txn.setReferenceType("ORDER");
        txn.setReferenceId(orderId);
        txn.setNotes(reason != null && !reason.isBlank() ? reason : "Item void for order " + orderId);
        txn.setCreatedBy(userId);
        txn.setEffectiveDate(LocalDate.now());

        Transaction saved = transactionRepository.save(txn);
        activityLogService.log(userId, userName, "VOID_ORDER",
            "Item void of ₱" + amount + " for order " + orderId
            + (reason != null && !reason.isBlank() ? " — " + reason : ""),
            "ORDER", orderId);
        return saved;
    }

    /**
     * POST /api/transactions/adjustment
     * Manual correction — amount may be positive or negative.
     * orderId is optional (null for non-order corrections).
     */
    @Transactional
    public Transaction recordAdjustment(String orderId, BigDecimal amount,
                                        String reason, Long userId, String userName) {
        if (amount == null) throw new RuntimeException("Adjustment amount is required");

        Transaction txn = new Transaction();
        txn.setTransactionCode("ADJ-" + System.currentTimeMillis());
        txn.setOrderId(orderId);
        txn.setTransactionType("ADJUSTMENT");
        txn.setAmount(amount);
        txn.setReferenceType(orderId != null ? "ORDER" : "MANUAL");
        txn.setReferenceId(orderId);
        txn.setNotes(reason != null && !reason.isBlank() ? reason : "Manual adjustment");
        txn.setCreatedBy(userId);
        txn.setEffectiveDate(LocalDate.now());

        Transaction saved = transactionRepository.save(txn);
        activityLogService.log(userId, userName, "ADJUSTMENT",
            "Adjustment of ₱" + amount
            + (orderId != null ? " for order " + orderId : "")
            + (reason != null && !reason.isBlank() ? " — " + reason : ""),
            orderId != null ? "ORDER" : "MANUAL",
            orderId);
        return saved;
    }

    /**
     * Creates a VOID to neutralise a SALE that was recorded at order-creation time.
     * Used when a force-close defers an order to the Collection page.
     * effective_date = originalOrderDate so it hits the SAME date as the original SALE
     * and zeroes it out of that day's totals before the daily report snapshot is taken.
     */
    @Transactional
    public Transaction recordDeferralVoid(Order order, Long userId) {
        // Default: the SALE being neutralised was recorded at order-creation time,
        // so the void hits the order's creation date.
        return recordDeferralVoid(order, userId, order.getCreatedAt().toLocalDate());
    }

    /**
     * Overload that lets the caller choose the effective date of the COLL-DEFER.
     *
     * Force-close and backdated-UNPAID orders record their SALE on the order-creation
     * date, so the default overload (effective_date = createdAt) nets them to zero.
     * A scheduled delivery marked "for collection", however, records its SALE on the
     * DELIVERY day ({@link OrderService#fulfillScheduledDelivery}), not on createdAt —
     * so its COLL-DEFER must be dated the delivery day to net on the same (still-open)
     * daily report. Financial totals are ledger-driven by effective_date, so mis-dating
     * the void would split SALE(+X) and COLL-DEFER(-X) across two different reports.
     */
    @Transactional
    public Transaction recordDeferralVoid(Order order, Long userId, LocalDate effectiveDate) {
        Transaction txn = new Transaction();
        txn.setTransactionCode("COLL-DEFER-" + order.getId());
        txn.setOrderId(order.getId());
        txn.setTransactionType("VOID");
        // NET basis: subtract any prior item-level voids so the COLL-DEFER
        // only reverses what the order still owes, not the gross total.
        // MUST stay in sync with recordCollectionSale() — both use the same
        // net basis so COLL-DEFER and COLL-SALE cancel each other exactly.
        BigDecimal gross  = order.getTotal()       != null ? order.getTotal()       : BigDecimal.ZERO;
        BigDecimal voided = order.getVoidedAmount() != null ? order.getVoidedAmount() : BigDecimal.ZERO;
        txn.setAmount(gross.subtract(voided).negate());
        txn.setReferenceType("ORDER");
        txn.setReferenceId(order.getId());
        txn.setNotes("Deferred to collection — " + order.getCustomerName());
        txn.setCreatedBy(userId);
        // Hits the SAME date as the SALE it neutralises so it nets to zero.
        txn.setEffectiveDate(effectiveDate);
        return transactionRepository.save(txn);
    }

    /**
     * Creates a SALE transaction when a deferred order is eventually collected.
     * effective_date = the actual collection date (cash-basis recognition): the COLL-DEFER(-X)
     * posted at force-close already removed the accrued sale from the order date, so posting
     * COLL-SALE(+X) on the collection date recognizes the revenue when the money was actually
     * received — not on the (possibly backdated) original order date.
     */
    @Transactional
    public Transaction recordCollectionSale(Order order, Long userId, LocalDate collectionDate) {
        Transaction txn = new Transaction();
        txn.setTransactionCode("COLL-SALE-" + order.getId());
        txn.setOrderId(order.getId());
        txn.setTransactionType("SALE");
        // NET basis: matches recordDeferralVoid() so COLL-DEFER and COLL-SALE
        // cancel each other exactly when the order carries prior item-level voids.
        // MUST stay in sync with recordDeferralVoid() — both use the same net basis.
        BigDecimal gross  = order.getTotal()       != null ? order.getTotal()       : BigDecimal.ZERO;
        BigDecimal voided = order.getVoidedAmount() != null ? order.getVoidedAmount() : BigDecimal.ZERO;
        txn.setAmount(gross.subtract(voided));
        txn.setReferenceType("ORDER");
        txn.setReferenceId(order.getId());
        txn.setNotes("Collection received — " + order.getCustomerName());
        txn.setCreatedBy(userId);
        // Post revenue on the actual collection date.
        txn.setEffectiveDate(collectionDate != null ? collectionDate : order.getCreatedAt().toLocalDate());
        return transactionRepository.save(txn);
    }

    // ── Guard helpers used by OrderService ────────────────────────────

    /**
     * Returns true iff this order was force-closed (a COLL-DEFER-{id} entry
     * exists) AND has not yet been collected (no COLL-SALE-{id} entry exists).
     *
     * Used by cancelOrder() to determine whether to skip the VOID ledger entry.
     * The three cancel lifecycles are:
     *
     *   1. Never deferred (normal ACTIVE/PENDING → CANCELLED):
     *      COLL-DEFER absent → returns false → caller writes VOID(-X). Net = 0. ✓
     *
     *   2. Deferred, uncollected (PENDING_COLLECTION → CANCELLED):
     *      COLL-DEFER present, COLL-SALE absent → returns true → caller skips VOID.
     *      Ledger: SALE(+X) + COLL-DEFER(-X) already nets to 0. ✓
     *
     *   3. Deferred then collected (DELIVERED after collection → CANCELLED):
     *      COLL-DEFER present AND COLL-SALE present → returns false → caller writes VOID(-X).
     *      Ledger: SALE(+X) + COLL-DEFER(-X) + COLL-SALE(+X) + VOID(-X) = 0. ✓
     *
     * A COLL-DEFER-only guard (ignoring COLL-SALE) would incorrectly return true
     * for lifecycle 3, leaving the ledger at +X after cancel.
     */
    public boolean isDeferredAndUncollected(String orderId) {
        return transactionRepository.existsByTransactionCode("COLL-DEFER-" + orderId)
            && !transactionRepository.existsByTransactionCode("COLL-SALE-"  + orderId);
    }

    // ── Read queries ───────────────────────────────────────────────────

    public List<Transaction> getByOrderId(String orderId) {
        return transactionRepository.findByOrderIdOrderByCreatedAtDesc(orderId);
    }

    public List<Transaction> getByDate(LocalDate date) {
        return transactionRepository.findByEffectiveDateOrderByCreatedAtDesc(date);
    }

    public List<Transaction> getByDateRange(LocalDate start, LocalDate end) {
        return transactionRepository.findByEffectiveDateBetweenOrderByCreatedAtDesc(start, end);
    }

    /** Filtered ledger list — type null means all types. */
    public List<Transaction> getLedger(String type, LocalDate start, LocalDate end) {
        return transactionRepository.findFiltered(type, start, end);
    }

    /** Per-type aggregate rows for the ledger report [type, sum, count]. */
    public List<Object[]> getLedgerReportBreakdown(LocalDate start, LocalDate end) {
        return transactionRepository.aggregateByTypeForDateRange(start, end);
    }

    // ── Aggregate helpers used by daily-close ─────────────────────────

    /** SUM of SALE transactions for a date. Always positive (or zero). */
    public BigDecimal getGrossSalesForDate(LocalDate date) {
        return transactionRepository.sumByDateAndType(date, "SALE");
    }

    /**
     * SUM of all reversal transactions for a date (REFUND + VOID + RETURN).
     * Returns a negative or zero value.
     */
    public BigDecimal getRefundsTotalForDate(LocalDate date) {
        BigDecimal refunds = transactionRepository.sumByDateAndType(date, "REFUND");
        BigDecimal voids   = transactionRepository.sumByDateAndType(date, "VOID");
        BigDecimal returns = transactionRepository.sumByDateAndType(date, "RETURN");
        return refunds.add(voids).add(returns);
    }

    /** SUM of ADJUSTMENT transactions for a date — may be positive or negative. */
    public BigDecimal getAdjustmentsTotalForDate(LocalDate date) {
        return transactionRepository.sumByDateAndType(date, "ADJUSTMENT");
    }

    /** Total number of transactions recorded on this date. */
    public long countTransactionsForDate(LocalDate date) {
        return transactionRepository.countByEffectiveDate(date);
    }

    // ── Private ───────────────────────────────────────────────────────
    private void validatePositiveAmount(BigDecimal amount, String label) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0)
            throw new RuntimeException(label + " amount must be a positive number");
    }
}
