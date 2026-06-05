package rrbm_backend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import rrbm_backend.dto.CancelForReplacementRequest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * M-26: double-void guard in cancelOrder() and cancelOrderForReplacement().
 *
 * ── Spec ──────────────────────────────────────────────────────────────────
 *
 * The intended combined-net ledger position for any cancel path is:
 *
 *   original order  →  net ₱0   (cancelled; revenue removed)
 *   replacement     →  net +₱Y  (the real sale)
 *   combined        →  ₱Y
 *
 * "remaining amount" used in the explicit-amount overload is defined at
 * OrderService.java:337-339:
 *   effectiveVoid = order.getTotal() - order.getVoidedAmount()
 * For fresh cancel-for-replacement (no prior item-level voids):
 *   effectiveVoid = 500 - 0 = 500 → stored as -500 in the ledger.
 *
 * ── Combined-net accounting (cancel-for-replacement, all lifecycles) ──────
 *
 *  Test | Pre-cancel original ledger                           | VOID written? | Original net | Combined
 *  -----+------------------------------------------------------+---------------+--------------+---------
 *  T5   | SALE(+500) + COLL-DEFER(-500) = 0                   | None (guard)  | ₱0           | ₱Y ✓
 *  T6   | SALE(+500)+COLL-DEFER(-500)+COLL-SALE(+500) = +500  | VOID(-500)    | ₱0           | ₱Y ✓
 *  T7   | SALE(+500) = +500                                    | VOID(-500)    | ₱0           | ₱Y ✓
 *
 * T4 (standard cancel after collection) uses the same guard:
 *  T4   | SALE(+500)+COLL-DEFER(-500)+COLL-SALE(+500) = +500  | VOID(-500)    | ₱0           | n/a ✓
 *
 * T5 passed for the right reason: the original IS at ₱0 before cancel
 * (SALE + COLL-DEFER = 0); skipping the VOID is economically correct.
 * Adding a VOID would produce -₱500 — that is the bug the guard prevents.
 */
@ExtendWith(MockitoExtension.class)
class CancelOrderM26Test {

    @Mock private OrderRepository    orderRepository;
    @Mock private UserRepository     userRepository;
    @Mock private InventoryService   inventoryService;
    @Mock private MasterKeyService   masterKeyService;
    @Mock private ActivityLogService activityLogService;
    @Mock private TransactionService transactionService;
    @Mock private OrderIdGenerator   orderIdGenerator;

    @InjectMocks
    private OrderService orderService;

    // ── helpers ────────────────────────────────────────────────────────

    private Order buildOrder(String id, String status) {
        Order o = new Order();
        o.setId(id);
        o.setStatus(status);
        o.setCustomerName("Test Customer");
        o.setTotal(BigDecimal.valueOf(500));
        o.setVoidedAmount(BigDecimal.ZERO);    // no prior item-level voids
        o.setCreatedAt(LocalDateTime.now().minusDays(1));
        o.setItems(new ArrayList<>());
        return o;
    }

    private User buildUser(Long id) {
        User u = new User();
        u.setId(id);
        u.setFullName("Test Admin");
        return u;
    }

    /** Stubs required by cancelOrder() for every standard-cancel variant. */
    private void stubForCancel(Order order, User user) {
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        // void methods (activityLogService.log, inventoryService.restoreStock*)
        // are no-ops on Mockito mocks by default — no explicit stubbing required.
    }

    /** Stubs required by cancelOrderForReplacement() for every replacement-cancel variant. */
    private void stubForReplacement(Order order, User user) {
        when(orderRepository.findByIdWithItems(order.getId())).thenReturn(Optional.of(order));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    /** Builds a minimal CancelForReplacementRequest. */
    private CancelForReplacementRequest replacementReq(String reason) {
        CancelForReplacementRequest req = new CancelForReplacementRequest();
        req.setMasterKey("rrbm2024");
        req.setReason(reason);
        req.setItems(new ArrayList<>());
        return req;
    }

    // ── T1 ─────────────────────────────────────────────────────────────

    /**
     * T1 — force-close → cancel.
     *
     * Pre-cancel ledger: SALE(+500) + COLL-DEFER(-500) = ₱0.
     * isDeferredAndUncollected = true.
     *
     * recordVoid must NOT be called — original net is already ₱0.
     * A second VOID(-500) would produce net = -₱500.
     */
    @Test
    void t1_forceClosed_uncollected_noVoidWritten() {
        Order order = buildOrder("T1-ORDER", "PENDING_COLLECTION");
        User  user  = buildUser(1L);
        stubForCancel(order, user);

        when(transactionService.isDeferredAndUncollected("T1-ORDER")).thenReturn(true);

        orderService.cancelOrder("T1-ORDER", 1L, "Customer cancelled after force-close");

        verify(transactionService, never()).recordVoid(any(Order.class), any(BigDecimal.class), anyLong(), anyString());
    }

    // ── T2 ─────────────────────────────────────────────────────────────

    /**
     * T2 — PENDING_COLLECTION status, no COLL-DEFER in the ledger.
     *
     * Invariant breach: force-close always writes COLL-DEFER atomically,
     * so this state should not occur in production. The guard must NOT
     * suppress the VOID in this edge case — the SALE would remain unmatched.
     *
     * isDeferredAndUncollected = false → recordVoid must be called.
     * voidedAmount = 0 → effectiveVoid = total(500) − 0 = 500.
     */
    @Test
    void t2_pendingCollection_noDefer_voidIsStillWritten() {
        Order order = buildOrder("T2-ORDER", "PENDING_COLLECTION");
        User  user  = buildUser(1L);
        stubForCancel(order, user);

        when(transactionService.isDeferredAndUncollected("T2-ORDER")).thenReturn(false);

        orderService.cancelOrder("T2-ORDER", 1L, "Cancel — invariant breach scenario");

        // cancelOrder now uses the net-amount 4-arg overload (Site 3 fix):
        // effectiveVoid = total(500) − voidedAmount(0) = 500.
        ArgumentCaptor<BigDecimal> amtCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(transactionService, times(1))
            .recordVoid(any(Order.class), amtCaptor.capture(), anyLong(), anyString());
        assertEquals(0, amtCaptor.getValue().compareTo(BigDecimal.valueOf(500)),
            "effectiveVoid must equal total(500) minus voidedAmount(0) = 500");
    }

    // ── T3 ─────────────────────────────────────────────────────────────

    /**
     * T3 — Normal ACTIVE order cancel (no deferral ever).
     *
     * Pre-cancel ledger: SALE(+500) = +₱500.
     * isDeferredAndUncollected = false → recordVoid must be called.
     *
     * Regression guard: the phantom-debit fix must not affect normal cancellations
     * where voidedAmount = 0. VOID(−500) still written → original net = ₱0.
     * voidedAmount = 0 → effectiveVoid = total(500) − 0 = 500.
     */
    @Test
    void t3_normalActive_cancel_voidWritten() {
        Order order = buildOrder("T3-ORDER", "ACTIVE");
        User  user  = buildUser(1L);
        stubForCancel(order, user);

        when(transactionService.isDeferredAndUncollected("T3-ORDER")).thenReturn(false);

        orderService.cancelOrder("T3-ORDER", 1L, "Wrong item ordered");

        // cancelOrder now uses the net-amount 4-arg overload (Site 3 fix):
        // effectiveVoid = total(500) − voidedAmount(0) = 500 — unchanged behaviour for clean orders.
        ArgumentCaptor<BigDecimal> amtCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(transactionService, times(1))
            .recordVoid(any(Order.class), amtCaptor.capture(), anyLong(), anyString());
        assertEquals(0, amtCaptor.getValue().compareTo(BigDecimal.valueOf(500)),
            "effectiveVoid must equal total(500) minus voidedAmount(0) = 500; clean cancel unchanged");
    }

    // ── T4 ─────────────────────────────────────────────────────────────

    /**
     * T4 — force-close → collect → cancel.
     *
     * Pre-cancel ledger: SALE(+500) + COLL-DEFER(−net) + COLL-SALE(+net) = +₱500 net.
     * isDeferredAndUncollected = false (COLL-SALE present, order IS collected).
     *
     * recordVoid MUST be called — revenue was re-recognised at collection
     * and now needs reversing.
     * voidedAmount = 0 → effectiveVoid = total(500) − 0 = 500.
     * After VOID(−500): net = ₱0. ✓
     */
    @Test
    void t4_deferred_then_collected_cancel_voidWritten() {
        Order order = buildOrder("T4-ORDER", "DELIVERED");
        User  user  = buildUser(1L);
        stubForCancel(order, user);

        when(transactionService.isDeferredAndUncollected("T4-ORDER")).thenReturn(false);

        orderService.cancelOrder("T4-ORDER", 1L, "Return after collection");

        // cancelOrder now uses the net-amount 4-arg overload (Site 3 fix):
        // effectiveVoid = total(500) − voidedAmount(0) = 500 — unchanged for clean orders.
        ArgumentCaptor<BigDecimal> amtCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(transactionService, times(1))
            .recordVoid(any(Order.class), amtCaptor.capture(), anyLong(), anyString());
        assertEquals(0, amtCaptor.getValue().compareTo(BigDecimal.valueOf(500)),
            "effectiveVoid must equal total(500) minus voidedAmount(0) = 500; collected cancel unchanged");
    }

    /**
     * T4-naive — documents the regression under a COLL-DEFER-only guard.
     *
     * If the guard returned true whenever COLL-DEFER exists — ignoring whether
     * COLL-SALE also exists — a collected-then-cancelled order would skip the VOID.
     * The ledger would remain at +₱500 (SALE+COLL-DEFER+COLL-SALE), not ₱0.
     *
     * This test stubs isDeferredAndUncollected = true (simulating the broken guard)
     * and asserts VOID is NOT called — proving the wrong ledger result.
     * This is the BUG; the corrected guard (T4) prevents it.
     */
    @Test
    void t4_naive_guard_regression_documents_wrong_behaviour() {
        Order order = buildOrder("T4N-ORDER", "DELIVERED");
        User  user  = buildUser(1L);
        stubForCancel(order, user);

        when(transactionService.isDeferredAndUncollected("T4N-ORDER")).thenReturn(true);

        orderService.cancelOrder("T4N-ORDER", 1L, "Return after collection — naive guard");

        // Under the naive guard VOID is skipped → ledger net stays +₱500. WRONG.
        verify(transactionService, never()).recordVoid(any(Order.class), any(BigDecimal.class), anyLong(), anyString());
    }

    // ── T5 ─────────────────────────────────────────────────────────────

    /**
     * T5 — cancel-for-replacement: PENDING_COLLECTION → CANCELLED (REPLACEMENT).
     *
     * Combined-net spec:
     *   Pre-cancel original: SALE(+500) + COLL-DEFER(-500) = ₱0.
     *   VOID written: none — isDeferredAndUncollected = true (guard fires).
     *   Original net after cancel: ₱0.
     *   Combined (orig + repl SALE(+Y)): ₱Y. ✓
     *
     * This test passed for the right reason: the original IS at ₱0 before
     * cancel (SALE + COLL-DEFER cancel each other). Skipping the VOID is
     * economically correct — adding one would produce -₱500 on the ledger.
     *
     * "remaining amount" per OrderService.java:337-339: 500 - 0 = 500.
     * The guard prevents recordVoid(order, 500, ...) from being called.
     */
    @Test
    void t5_cancelForReplacement_pendingCollection_noVoidWritten() {
        Order order = buildOrder("T5-ORDER", "PENDING_COLLECTION");
        User  user  = buildUser(1L);
        stubForReplacement(order, user);

        when(transactionService.isDeferredAndUncollected("T5-ORDER")).thenReturn(true);

        orderService.cancelOrderForReplacement("T5-ORDER", replacementReq("Re-encoding required"), 1L);

        // VOID must NOT be written — original is already at ₱0 (SALE + COLL-DEFER)
        verify(transactionService, never()).recordVoid(any(Order.class), any(BigDecimal.class), anyLong(), anyString());
    }

    // ── T6 ─────────────────────────────────────────────────────────────

    /**
     * T6 — cancel-for-replacement: force-close → collect (DELIVERED) → cancel-for-replacement.
     *
     * High-risk cell: the original has been collected so revenue is live in the ledger.
     * The replacement overload uses the remaining-amount calculation defined at
     * OrderService.java:337-339: effectiveVoid = total - voidedAmount = 500 - 0 = 500.
     *
     * Combined-net spec:
     *   Pre-cancel original: SALE(+500) + COLL-DEFER(-500) + COLL-SALE(+500) = +₱500.
     *   isDeferredAndUncollected = false (COLL-SALE exists → NOT uncollected).
     *   VOID written: VOID(-500) → original net = +500 - 500 = ₱0.
     *   Combined (orig + repl SALE(+Y)): ₱Y. ✓
     *
     * Confirms original does NOT silently retain revenue after cancel.
     */
    @Test
    void t6_cancelForReplacement_deferred_collected_voidWritten() {
        Order order = buildOrder("T6-ORDER", "DELIVERED");
        User  user  = buildUser(1L);
        stubForReplacement(order, user);

        // COLL-DEFER and COLL-SALE both exist → collected, NOT uncollected → false
        when(transactionService.isDeferredAndUncollected("T6-ORDER")).thenReturn(false);

        orderService.cancelOrderForReplacement("T6-ORDER", replacementReq("Replace after collection"), 1L);

        // VOID(-500) must be written so original net = ₱0
        ArgumentCaptor<BigDecimal> amtCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(transactionService, times(1))
            .recordVoid(any(Order.class), amtCaptor.capture(), anyLong(), anyString());

        // "remaining amount" = total(500) - voidedAmount(0) = 500 [OrderService.java:337-339]
        assertEquals(0, amtCaptor.getValue().compareTo(BigDecimal.valueOf(500)),
            "effectiveVoid must equal total(500) minus voidedAmount(0); original nets to ₱0");
    }

    // ── P1 ─────────────────────────────────────────────────────────────

    /**
     * P1 — prior TIER-1 item void → force-close → standard cancel.
     *
     * Setup: voidedAmount = ₱100 (TIER-1 void already applied), total = ₱500.
     * isDeferredAndUncollected = true (COLL-DEFER exists, no COLL-SALE).
     *
     * cancelOrder() correctly skips the VOID entry (M-26 guard fires). ✓
     *
     * ── Phantom-balance gap (pre-existing bug in recordDeferralVoid) ────────
     *
     * The combined ledger for this order across all dates is:
     *
     *   SALE(+500)       creation date — gross, per recordSale():60
     *   VOID(−100)       item-void date — per recordItemVoid(), effectiveDate=today
     *   COLL-DEFER(−500) creation date — TransactionService.java:311 uses gross total
     *   [no VOID]        cancel — M-26 guard fires correctly
     *
     *   SUM = +500 − 100 − 500 = −100  ← PHANTOM DEBIT, not ₱0.
     *
     * Root cause: recordDeferralVoid:311 uses order.getTotal().negate() (gross),
     * not (order.getTotal() − order.getVoidedAmount()).negate() (net).
     * Correct COLL-DEFER would be −400; then SALE(+500)+VOID(−100)+COLL-DEFER(−400) = 0.
     *
     * This is NOT an M-26 regression. Before M-26, cancelOrder also wrote VOID(−500),
     * producing SUM = −600 instead of −100. M-26 reduces but does not eliminate the bug.
     *
     * Fix required: TransactionService.java:311 — use net-of-voidedAmount for COLL-DEFER.
     */
    @Test
    void p1_deferredUncollected_withPriorItemVoid_phantomBalanceDocumented() {
        Order order = buildOrder("P1-ORDER", "PENDING_COLLECTION");
        order.setVoidedAmount(BigDecimal.valueOf(100)); // TIER-1 item void already recorded
        User  user  = buildUser(1L);
        stubForCancel(order, user);

        when(transactionService.isDeferredAndUncollected("P1-ORDER")).thenReturn(true);

        orderService.cancelOrder("P1-ORDER", 1L, "Cancelled after item void");

        // cancelOrder correctly writes NO VOID — M-26 guard fires (COLL-DEFER exists, no COLL-SALE).
        // The ledger net is ₱0: recordDeferralVoid now uses net basis (Site 1 fix),
        // so SALE(+500) + VOID_item(−100) + COLL-DEFER(−400) = ₱0.
        verify(transactionService, never()).recordVoid(any(Order.class), any(BigDecimal.class), anyLong(), anyString());
    }

    // ── P2 ─────────────────────────────────────────────────────────────

    /**
     * P2 — prior TIER-1 item void → force-close → collect → cancel-for-replacement.
     *
     * Setup: voidedAmount = ₱100, total = ₱500.
     * isDeferredAndUncollected = false (COLL-SALE exists after collection).
     *
     * Combined-net spec (force-close and collect both use gross total):
     *
     *   SALE(+500)       creation date
     *   VOID(−100)       item-void date
     *   COLL-DEFER(−500) creation date — gross (bug noted in P1, but pairs with COLL-SALE)
     *   COLL-SALE(+500)  creation date — gross (COLL-DEFER and COLL-SALE cancel each other)
     *   VOID(−400)       cancel date   — effectiveVoid = 500 − 100 = 400
     *
     *   SUM = +500 − 100 − 500 + 500 − 400 = 0 ✓
     *
     * cancelOrderForReplacement uses effectiveVoid = grossTotal − voidedAmount (line 337-339),
     * which correctly handles the prior item void even though COLL-DEFER/COLL-SALE are gross.
     *
     * "remaining amount" per OrderService.java:337-339: 500 − 100 = 400.
     */
    @Test
    void p2_cancelForReplacement_withPriorItemVoid_effectiveVoidIsNetOfPriorVoids() {
        Order order = buildOrder("P2-ORDER", "DELIVERED");
        order.setVoidedAmount(BigDecimal.valueOf(100)); // prior TIER-1 item void
        User  user  = buildUser(1L);
        stubForReplacement(order, user);

        when(transactionService.isDeferredAndUncollected("P2-ORDER")).thenReturn(false);

        orderService.cancelOrderForReplacement("P2-ORDER", replacementReq("Replace after partial void"), 1L);

        // effectiveVoid = grossTotal(500) − voidedAmount(100) = 400 — per OrderService.java:337-339
        ArgumentCaptor<BigDecimal> amtCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(transactionService, times(1))
            .recordVoid(any(Order.class), amtCaptor.capture(), anyLong(), anyString());

        assertEquals(0, amtCaptor.getValue().compareTo(BigDecimal.valueOf(400)),
            "effectiveVoid must equal total(500) minus voidedAmount(100); COLL-DEFER/COLL-SALE cancel each other, combined net = ₱0");
    }

    // ── T7 ─────────────────────────────────────────────────────────────

    /**
     * T7 — cancel-for-replacement: ACTIVE → cancel-for-replacement.
     *
     * Combined-net spec:
     *   Pre-cancel original: SALE(+500) = +₱500.
     *   isDeferredAndUncollected = false (no COLL-DEFER at all).
     *   VOID written: VOID(-500) → original net = ₱0.
     *   Combined (orig + repl SALE(+Y)): ₱Y. ✓
     *
     * Regression guard: the M-26 fix must not affect the common non-deferred
     * cancel-for-replacement path.
     */
    @Test
    void t7_cancelForReplacement_normalActive_voidWritten() {
        Order order = buildOrder("T7-ORDER", "ACTIVE");
        User  user  = buildUser(1L);
        stubForReplacement(order, user);

        when(transactionService.isDeferredAndUncollected("T7-ORDER")).thenReturn(false);

        orderService.cancelOrderForReplacement("T7-ORDER", replacementReq("Wrong item encoded"), 1L);

        // VOID(-500) must be written so original net = ₱0
        ArgumentCaptor<BigDecimal> amtCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(transactionService, times(1))
            .recordVoid(any(Order.class), amtCaptor.capture(), anyLong(), anyString());

        assertEquals(0, amtCaptor.getValue().compareTo(BigDecimal.valueOf(500)),
            "effectiveVoid must equal total(500) minus voidedAmount(0); original nets to ₱0");
    }
}
