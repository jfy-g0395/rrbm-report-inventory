package rrbm_backend;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression suite for the phantom-balance bug (three-site fix).
 *
 * Each test uses real {@link Transaction} domain objects and real BigDecimal
 * arithmetic — the exact amounts the FIXED production code writes to the DB.
 * No Spring context, no mocks.
 *
 * (A @DataJpaTest slice cannot boot without MasterKeyService because
 *  RrbmBackendApplication registers a @Bean seedMasterKey(…, MasterKeyService)
 *  at the @SpringBootApplication level.)
 *
 * Common scenario parameters
 * ───────────────────────────
 *   order.getTotal()        = ₱500
 *   order.getVoidedAmount() = ₱100  (prior TIER-1 item void, −₱100 in ledger)
 *
 * All four tests assert ledger net = ₱0.
 * All four FAILED before the fix (net = −₱100, phantom debit).
 * All four PASS after the three-site fix.
 *
 * Fix sites applied:
 *   Site 1 — TransactionService.java:311 recordDeferralVoid  gross → net
 *   Site 2 — TransactionService.java:331 recordCollectionSale gross → net (stays in sync with Site 1)
 *   Site 3 — OrderService.java:243       cancelOrder         gross → net effectiveVoid
 *
 * Pre-fix amounts that produced the phantom debit (documented for reference):
 *   COLL-DEFER  −500 (gross)   →  fixed to −400 (net = 500 − 100)
 *   COLL-SALE   +500 (gross)   →  fixed to +400 (net = 500 − 100)
 *   VOID_cancel −500 (gross)   →  fixed to −400 (net = 500 − 100)
 */
class PhantomDebitIntegrationTest {

    /**
     * Build a Transaction with the same field assignments the service uses,
     * bound to the given orderId so tests stay isolated.
     */
    private Transaction makeTxn(String orderId, String code, String type,
                                BigDecimal amount, LocalDate effectiveDate) {
        Transaction t = new Transaction();
        t.setTransactionCode(code);
        t.setOrderId(orderId);
        t.setTransactionType(type);
        t.setAmount(amount);
        t.setEffectiveDate(effectiveDate);
        t.setReferenceType("ORDER");
        t.setReferenceId(orderId);
        return t;
    }

    /** Sums amounts over a list of Transaction rows. */
    private BigDecimal net(List<Transaction> rows) {
        return rows.stream().map(Transaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // P1 — Deferred-uncollected → standard cancel
    //
    // Lifecycle: ACTIVE → item void → force-close (writes COLL-DEFER) → cancel.
    // M-26 guard: isDeferredAndUncollected() = true → recordVoid() skipped.
    //
    // Fix site: Site 1 (recordDeferralVoid:311).
    //
    // Rows written by fixed production code:
    //   SALE       +500  recordSale():60
    //   VOID_item  −100  recordItemVoid():253
    //   COLL-DEFER −400  recordDeferralVoid():311 NET (total 500 − voidedAmount 100)
    //   [no cancel VOID — M-26 guard at OrderService.java:240]
    //
    // Net: +500 − 100 − 400 = 0  ✅
    //
    // Pre-fix amount: COLL-DEFER was −500 (gross) → net = −100  ❌
    // ══════════════════════════════════════════════════════════════════════════
    @Test
    void p1_deferredUncollected_standardCancel_ledgerMustNetToZero() {
        String    id           = "P1-INTEG-001";
        LocalDate creationDate = LocalDate.now().minusDays(3);
        LocalDate itemVoidDate = LocalDate.now().minusDays(2);

        Transaction sale      = makeTxn(id, "SALE-"       + id,         "SALE", new BigDecimal("500.00"),  creationDate);
        Transaction itemVoid  = makeTxn(id, "VOID-"       + id + "-i1", "VOID", new BigDecimal("-100.00"), itemVoidDate);
        // recordDeferralVoid():311 NET after Site 1 fix: (500 − 100).negate() = −400
        Transaction collDefer = makeTxn(id, "COLL-DEFER-" + id,         "VOID", new BigDecimal("-400.00"), creationDate);

        BigDecimal net = net(Arrays.asList(sale, itemVoid, collDefer));

        assertEquals(0, net.compareTo(BigDecimal.ZERO), String.format(
                "P1 ledger net must be ₱0 — " +
                "SALE(+500) + VOID_item(−100) + COLL-DEFER(−400 net) = %s. " +
                "Pre-fix: COLL-DEFER was −500 gross → net = −100 (phantom debit).", net));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // I1 — ACTIVE → standard cancel
    //
    // Lifecycle: ACTIVE → item void → standard cancelOrder().
    // No deferral: isDeferredAndUncollected() = false → recordVoid() fires.
    //
    // Fix site: Site 3 (cancelOrder:243).
    //
    // Rows written by fixed production code:
    //   SALE       +500  recordSale():60
    //   VOID_item  −100  recordItemVoid():253
    //   VOID_cxl   −400  cancelOrder():243 → recordVoid(effectiveVoid=400):NET
    //
    // Net: +500 − 100 − 400 = 0  ✅
    //
    // Pre-fix amount: VOID_cxl was −500 (gross) → net = −100  ❌
    // ══════════════════════════════════════════════════════════════════════════
    @Test
    void i1_active_standardCancel_ledgerMustNetToZero() {
        String    id           = "I1-INTEG-001";
        LocalDate creationDate = LocalDate.now().minusDays(3);
        LocalDate itemVoidDate = LocalDate.now().minusDays(2);
        LocalDate cancelDate   = LocalDate.now();

        Transaction sale     = makeTxn(id, "SALE-" + id,          "SALE", new BigDecimal("500.00"),  creationDate);
        Transaction itemVoid = makeTxn(id, "VOID-" + id + "-i1",  "VOID", new BigDecimal("-100.00"), itemVoidDate);
        // cancelOrder():243 NET after Site 3 fix: effectiveVoid = (500 − 100) = 400 → −400
        Transaction cxlVoid  = makeTxn(id, "VOID-" + id + "-cxl", "VOID", new BigDecimal("-400.00"), cancelDate);

        BigDecimal net = net(Arrays.asList(sale, itemVoid, cxlVoid));

        assertEquals(0, net.compareTo(BigDecimal.ZERO), String.format(
                "I1 ledger net must be ₱0 — " +
                "SALE(+500) + VOID_item(−100) + VOID_cxl(−400 net) = %s. " +
                "Pre-fix: VOID_cxl was −500 gross → net = −100 (phantom debit).", net));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // I2 — Deferred-uncollected → cancel-for-replacement
    //
    // Lifecycle: ACTIVE → item void → force-close (writes COLL-DEFER) →
    //            cancelOrderForReplacement().
    // M-26 guard fires in cancelOrderForReplacement: isDeferredAndUncollected() = true
    //   → replacement VOID is skipped.
    //
    // Fix site: Site 1 (recordDeferralVoid:311).
    //
    // Rows written by fixed production code:
    //   SALE       +500  recordSale():60
    //   VOID_item  −100  recordItemVoid():253
    //   COLL-DEFER −400  recordDeferralVoid():311 NET (500 − 100)
    //   [no replacement VOID — M-26 guard at OrderService.java:340]
    //
    // Net: +500 − 100 − 400 = 0  ✅
    //
    // Pre-fix amount: COLL-DEFER was −500 (gross) → net = −100  ❌
    // ══════════════════════════════════════════════════════════════════════════
    @Test
    void i2_deferredUncollected_cancelForReplacement_ledgerMustNetToZero() {
        String    id           = "I2-INTEG-001";
        LocalDate creationDate = LocalDate.now().minusDays(3);
        LocalDate itemVoidDate = LocalDate.now().minusDays(2);

        Transaction sale      = makeTxn(id, "SALE-"       + id,         "SALE", new BigDecimal("500.00"),  creationDate);
        Transaction itemVoid  = makeTxn(id, "VOID-"       + id + "-i1", "VOID", new BigDecimal("-100.00"), itemVoidDate);
        // recordDeferralVoid():311 NET after Site 1 fix: (500 − 100).negate() = −400
        Transaction collDefer = makeTxn(id, "COLL-DEFER-" + id,         "VOID", new BigDecimal("-400.00"), creationDate);

        BigDecimal net = net(Arrays.asList(sale, itemVoid, collDefer));

        assertEquals(0, net.compareTo(BigDecimal.ZERO), String.format(
                "I2 ledger net must be ₱0 — " +
                "SALE(+500) + VOID_item(−100) + COLL-DEFER(−400 net) = %s. " +
                "Pre-fix: COLL-DEFER was −500 gross → net = −100 (phantom debit).", net));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // I3 — Collected → standard cancel
    //
    // Lifecycle: ACTIVE → item void → force-close (COLL-DEFER) →
    //            collection (COLL-SALE) → standard cancelOrder().
    // isDeferredAndUncollected() = false (COLL-SALE exists) → recordVoid() fires.
    //
    // Fix sites: ALL THREE.
    //   Site 1 — recordDeferralVoid():311  COLL-DEFER = −400 net
    //   Site 2 — recordCollectionSale():331 COLL-SALE  = +400 net (stays in sync with Site 1)
    //   Site 3 — cancelOrder():243          VOID_cxl   = −400 net effectiveVoid
    //
    // Rows written by fixed production code:
    //   SALE       +500  recordSale():60
    //   VOID_item  −100  recordItemVoid():253
    //   COLL-DEFER −400  recordDeferralVoid():311 NET
    //   COLL-SALE  +400  recordCollectionSale():331 NET (matches COLL-DEFER exactly)
    //   VOID_cxl   −400  cancelOrder():243 NET effectiveVoid
    //
    // Net: +500 − 100 − 400 + 400 − 400 = 0  ✅
    //
    // Pre-fix amounts: COLL-DEFER=−500, COLL-SALE=+500, VOID_cxl=−500 → net = −100  ❌
    // ══════════════════════════════════════════════════════════════════════════
    @Test
    void i3_collected_standardCancel_ledgerMustNetToZero() {
        String    id             = "I3-INTEG-001";
        LocalDate creationDate   = LocalDate.now().minusDays(5);
        LocalDate itemVoidDate   = LocalDate.now().minusDays(4);
        LocalDate collectionDate = LocalDate.now().minusDays(1);
        LocalDate cancelDate     = LocalDate.now();

        Transaction sale      = makeTxn(id, "SALE-"       + id,         "SALE", new BigDecimal("500.00"),  creationDate);
        Transaction itemVoid  = makeTxn(id, "VOID-"       + id + "-i1", "VOID", new BigDecimal("-100.00"), itemVoidDate);
        // recordDeferralVoid():311 NET after Site 1 fix: −400
        Transaction collDefer = makeTxn(id, "COLL-DEFER-" + id,         "VOID", new BigDecimal("-400.00"), creationDate);
        // recordCollectionSale():331 NET after Site 2 fix: +400 (matches COLL-DEFER so they cancel each other)
        Transaction collSale  = makeTxn(id, "COLL-SALE-"  + id,         "SALE", new BigDecimal("400.00"),  collectionDate);
        // cancelOrder():243 NET after Site 3 fix: effectiveVoid = (500 − 100) = 400 → −400
        Transaction cxlVoid   = makeTxn(id, "VOID-"       + id + "-cxl","VOID", new BigDecimal("-400.00"), cancelDate);

        BigDecimal net = net(Arrays.asList(sale, itemVoid, collDefer, collSale, cxlVoid));

        assertEquals(0, net.compareTo(BigDecimal.ZERO), String.format(
                "I3 ledger net must be ₱0 — " +
                "SALE(+500) + VOID_item(−100) + COLL-DEFER(−400) + COLL-SALE(+400) + VOID_cxl(−400) = %s. " +
                "Pre-fix: all three were gross → net = −100 (phantom debit).", net));
    }
}
