package rrbm_backend;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Exposes the transaction ledger over HTTP.
 *
 * All write endpoints (void, adjustment) require a valid JWT.
 * Read endpoints are open (protected by the global JWT filter).
 */
@RestController
@RequestMapping("/api/transactions")
@CrossOrigin(origins = "*")
public class TransactionController {

    private final TransactionService  transactionService;
    private final UserRepository      userRepository;
    private final JwtUtil             jwtUtil;

    public TransactionController(TransactionService transactionService,
                                  UserRepository userRepository,
                                  JwtUtil jwtUtil) {
        this.transactionService = transactionService;
        this.userRepository     = userRepository;
        this.jwtUtil            = jwtUtil;
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private Long userIdFromHeader(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return null;
        return jwtUtil.extractUserId(authHeader.substring(7));
    }

    private String userNameFromId(Long userId) {
        if (userId == null) return "Unknown";
        return userRepository.findById(userId)
            .map(User::getFullName)
            .orElse("Unknown");
    }

    // ── Write endpoints ───────────────────────────────────────────────

    /**
     * POST /api/transactions/adjustment
     *
     * Manual accounting correction.  Amount may be positive (upward
     * adjustment) or negative (downward).  orderId is optional.
     *
     * Body: { "orderId": "..." (optional), "amount": "-200.00", "reason": "..." }
     */
    @PostMapping("/adjustment")
    public ResponseEntity<?> issueAdjustment(
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        Long userId = userIdFromHeader(authHeader);
        if (userId == null)
            return ResponseEntity.status(401).body(Map.of("message", "Unauthorized"));

        String orderId   = body.get("orderId");
        String amountStr = body.get("amount");
        String reason    = body.get("reason");

        if (amountStr == null)
            return ResponseEntity.badRequest().body(Map.of("message", "amount is required"));

        BigDecimal amount;
        try { amount = new BigDecimal(amountStr); }
        catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(Map.of("message", "Invalid amount format"));
        }

        try {
            Transaction txn = transactionService.recordAdjustment(
                orderId, amount, reason, userId, userNameFromId(userId));
            return ResponseEntity.status(HttpStatus.CREATED).body(toDto(txn));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    // ── Read endpoints ────────────────────────────────────────────────

    /**
     * GET /api/transactions/order/{orderId}
     * Returns the full ledger history for one order.
     */
    @GetMapping("/order/{orderId}")
    public ResponseEntity<?> getByOrder(@PathVariable String orderId) {
        List<Transaction> txns = transactionService.getByOrderId(orderId);
        return ResponseEntity.ok(txns.stream().map(this::toDto).collect(Collectors.toList()));
    }

    /**
     * GET /api/transactions/date-range?start=YYYY-MM-DD&end=YYYY-MM-DD
     * Returns all transactions with effective_date in [start, end].
     */
    @GetMapping("/date-range")
    public ResponseEntity<?> getByDateRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
        List<Transaction> txns = transactionService.getByDateRange(start, end);
        return ResponseEntity.ok(txns.stream().map(this::toDto).collect(Collectors.toList()));
    }

    /**
     * GET /api/transactions/accounting-summary?date=YYYY-MM-DD  (defaults to today)
     *
     * Returns the accounting breakdown for a single date:
     *   grossSales      — sum of SALE transactions
     *   refundsTotal    — sum of REFUND + VOID + RETURN (negative)
     *   adjustmentsTotal — sum of ADJUSTMENT transactions (positive or negative)
     *   netSales        — grossSales + refundsTotal + adjustmentsTotal
     */
    @GetMapping("/accounting-summary")
    public ResponseEntity<?> getAccountingSummary(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        LocalDate target = date != null ? date : LocalDate.now();

        BigDecimal grossSales    = transactionService.getGrossSalesForDate(target);
        BigDecimal refundsTotal  = transactionService.getRefundsTotalForDate(target);
        BigDecimal adjustments   = transactionService.getAdjustmentsTotalForDate(target);
        BigDecimal netSales      = grossSales.add(refundsTotal).add(adjustments);
        long       txnCount      = transactionService.countTransactionsForDate(target);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("date",             target.toString());
        summary.put("grossSales",       grossSales);
        summary.put("refundsTotal",     refundsTotal);
        summary.put("adjustmentsTotal", adjustments);
        summary.put("netSales",         netSales);
        summary.put("totalTransactions", txnCount);
        return ResponseEntity.ok(summary);
    }

    /**
     * GET /api/transactions/ledger?type=SALE&start=YYYY-MM-DD&end=YYYY-MM-DD
     *
     * Filtered transaction list for the ledger view.
     * type is optional — omit to return all types.
     * start/end default to today if omitted.
     */
    @GetMapping("/ledger")
    public ResponseEntity<?> getLedger(
            @RequestParam(required = false) String type,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {

        LocalDate from      = start != null ? start : LocalDate.now();
        LocalDate to        = end   != null ? end   : LocalDate.now();
        String    typeFilter = (type != null && !type.isBlank()) ? type.toUpperCase() : null;

        List<Transaction> txns = transactionService.getLedger(typeFilter, from, to);
        return ResponseEntity.ok(txns.stream().map(this::toDto).collect(Collectors.toList()));
    }

    /**
     * GET /api/transactions/ledger/report?start=YYYY-MM-DD&end=YYYY-MM-DD
     *
     * Comprehensive date-range report: per-type totals + net sales aggregate.
     * start/end default to today if omitted.
     *
     * Response:
     *   startDate, endDate,
     *   grossSales      — sum of SALE amounts
     *   voidTotal       — sum of VOID amounts (negative)
     *   returnTotal     — sum of RETURN + REFUND amounts (negative)
     *   adjustmentsTotal — sum of ADJUSTMENT amounts (positive or negative)
     *   netSales        — grossSales + voidTotal + returnTotal + adjustmentsTotal
     *   totalCount      — total number of transactions in range
     *   breakdown       — [ { type, total, count } ] one row per distinct type
     */
    @GetMapping("/ledger/report")
    public ResponseEntity<?> getLedgerReport(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {

        LocalDate from = start != null ? start : LocalDate.now();
        LocalDate to   = end   != null ? end   : LocalDate.now();

        List<Object[]> rows = transactionService.getLedgerReportBreakdown(from, to);

        BigDecimal grossSales       = BigDecimal.ZERO;
        BigDecimal voidTotal        = BigDecimal.ZERO;
        BigDecimal returnTotal      = BigDecimal.ZERO;
        BigDecimal adjustmentsTotal = BigDecimal.ZERO;
        long       totalCount       = 0;

        List<Map<String, Object>> breakdown = new ArrayList<>();
        for (Object[] row : rows) {
            String     t = (String) row[0];
            BigDecimal s = row[1] instanceof BigDecimal ? (BigDecimal) row[1]
                         : new BigDecimal(row[1].toString());
            long c = ((Number) row[2]).longValue();
            totalCount += c;

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type",  t);
            entry.put("total", s);
            entry.put("count", c);
            breakdown.add(entry);

            switch (t) {
                case "SALE"       -> grossSales       = grossSales.add(s);
                case "VOID"       -> voidTotal        = voidTotal.add(s);
                case "RETURN",
                     "REFUND"     -> returnTotal      = returnTotal.add(s);
                case "ADJUSTMENT",
                     "DISCOUNT"   -> adjustmentsTotal = adjustmentsTotal.add(s);
            }
        }

        BigDecimal netSales = grossSales.add(voidTotal).add(returnTotal).add(adjustmentsTotal);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("startDate",        from.toString());
        report.put("endDate",          to.toString());
        report.put("grossSales",       grossSales);
        report.put("voidTotal",        voidTotal);
        report.put("returnTotal",      returnTotal);
        report.put("adjustmentsTotal", adjustmentsTotal);
        report.put("netSales",         netSales);
        report.put("totalCount",       totalCount);
        report.put("breakdown",        breakdown);

        return ResponseEntity.ok(report);
    }

    // ── DTO mapper ────────────────────────────────────────────────────

    private Map<String, Object> toDto(Transaction t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",              t.getId());
        m.put("transactionCode", t.getTransactionCode());
        m.put("orderId",         t.getOrderId());
        m.put("transactionType", t.getTransactionType());
        m.put("amount",          t.getAmount());
        m.put("referenceType",   t.getReferenceType());
        m.put("referenceId",     t.getReferenceId());
        m.put("notes",           t.getNotes());
        m.put("createdBy",       t.getCreatedBy());
        m.put("createdAt",       t.getCreatedAt()     != null ? t.getCreatedAt().toString()     : null);
        m.put("effectiveDate",   t.getEffectiveDate() != null ? t.getEffectiveDate().toString() : null);
        return m;
    }
}
