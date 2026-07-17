package rrbm_backend;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import rrbm_backend.dto.CreateOrderRequest;

import java.time.LocalDate;
import java.util.*;

/**
 * Backdated "Add Records" commit endpoint (S2).
 *
 * <p>{@code POST /api/backdated/commit} accepts a session list of backdated orders and expenses and
 * commits them together, reusing the exact same build + creation logic as the live New Order /
 * Expense screens (via {@link OrderService#createBackdatedOrder} and
 * {@link ExpenseController#createBackdatedExpense}). Each entry carries its own {@code date} and a
 * {@code recordingOnly} toggle.
 *
 * <p>Processing mirrors {@code ImportController.commitImport}: admin-security-key gated, each entry in
 * its own try/catch (a bad row never blocks the rest), and orders are tagged {@code imported=true} /
 * {@code lateImported=true} (the latter when that date's report is already closed). Unpaid / COD
 * orders are routed to the Collections page by {@code createBackdatedOrder}.
 *
 * <p><b>S2 scope:</b> commit + collection routing only. The endpoint returns the distinct affected
 * dates; the daily-report recompute + "amended" marker for closed dates is wired in S3.
 *
 * <p>{@code ImportController} and its history endpoints remain untouched.
 */
@RestController
@RequestMapping("/api/backdated")
public class BackdatedEntryController {

    private static final Logger log = LoggerFactory.getLogger(BackdatedEntryController.class);

    private final JwtUtil                jwtUtil;
    private final UserRepository         userRepository;
    private final OrderService           orderService;
    private final ExpenseController      expenseController;
    private final DailyReportService     dailyReportService;
    private final BCryptPasswordEncoder  passwordEncoder = new BCryptPasswordEncoder();

    /** Lenient mapper: order entries carry extra keys (date, recordingOnly, paymentStatus). */
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public BackdatedEntryController(JwtUtil jwtUtil,
                                    UserRepository userRepository,
                                    OrderService orderService,
                                    ExpenseController expenseController,
                                    DailyReportService dailyReportService) {
        this.jwtUtil            = jwtUtil;
        this.userRepository     = userRepository;
        this.orderService       = orderService;
        this.expenseController  = expenseController;
        this.dailyReportService = dailyReportService;
    }

    private Long userIdFromHeader(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return null;
        return jwtUtil.extractUserId(authHeader.substring(7));
    }

    /** Same gate as ImportController.checkKey — verify the caller's personal admin security key. */
    private ResponseEntity<?> checkKey(User user, String rawKey) {
        if (user.getAdminSecurityKey() == null
                || !passwordEncoder.matches(rawKey.trim(), user.getAdminSecurityKey())) {
            return ResponseEntity.status(403).body(Map.of("error", "Invalid security key"));
        }
        return null;
    }

    // ── POST /api/backdated/commit ────────────────────────────────────────────
    @PostMapping("/commit")
    public ResponseEntity<?> commit(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, Object> body) {

        Long userId = userIdFromHeader(authHeader);
        if (userId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        User user = userRepository.findById(userId).orElse(null);
        if (user == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        ResponseEntity<?> keyErr = checkKey(user, body.getOrDefault("adminSecurityKey", "").toString());
        if (keyErr != null) return keyErr;

        List<Map<String, Object>> orders   = asMapList(body.get("orders"));
        List<Map<String, Object>> expenses = asMapList(body.get("expenses"));
        // "Create daily report" checkbox on the Add Records page.
        boolean createReport = Boolean.TRUE.equals(body.get("createReport"));

        List<Map<String, Object>> committedOrders   = new ArrayList<>();
        List<Map<String, Object>> committedExpenses = new ArrayList<>();
        List<Map<String, Object>> collections       = new ArrayList<>();
        List<Map<String, Object>> errors            = new ArrayList<>();
        TreeSet<LocalDate>        affectedDates      = new TreeSet<>();
        // Earliest date carrying an entry that actually moved cash-on-hand — drives the
        // cash cascade in the recompute (a cash shift before today re-freezes later snapshots).
        LocalDate                 earliestCashDate   = null;

        // ── Orders ────────────────────────────────────────────────────────────
        for (int i = 0; i < orders.size(); i++) {
            Map<String, Object> entry = orders.get(i);
            try {
                LocalDate date = parseDate(entry.get("date"));
                requireNotFuture(date);   // a day that hasn't happened yet cannot hold records
                boolean recordingOnly = Boolean.TRUE.equals(entry.get("recordingOnly"));
                String paymentStatus = entry.get("paymentStatus") == null
                        ? null : entry.get("paymentStatus").toString();

                CreateOrderRequest req = mapper.convertValue(entry, CreateOrderRequest.class);
                Order saved = orderService.createBackdatedOrder(req, paymentStatus, userId, date, recordingOnly);
                affectedDates.add(date);

                // Cash-affecting iff a cash-in was actually posted: not recording-only, CASH mode,
                // and not deferred to collection (UNPAID/PENDING_COLLECTION posts no cash here).
                boolean cashAffecting = !recordingOnly
                        && "CASH".equalsIgnoreCase(saved.getPaymentMode())
                        && !"PENDING_COLLECTION".equals(saved.getStatus());
                if (cashAffecting && (earliestCashDate == null || date.isBefore(earliestCashDate))) {
                    earliestCashDate = date;
                }

                boolean toCollection = "PENDING_COLLECTION".equals(saved.getStatus())
                        || "PENDING".equals(saved.getStatus());

                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("orderId",       saved.getId());
                summary.put("date",          date.toString());
                summary.put("customer",      saved.getCustomerName());
                summary.put("total",         saved.getTotal());
                summary.put("status",        saved.getStatus());
                summary.put("paymentStatus", saved.getPaymentStatus());
                summary.put("collection",    toCollection);
                summary.put("lateImported",  saved.isLateImported());
                summary.put("recordingOnly", recordingOnly);
                committedOrders.add(summary);

                if (toCollection) {
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("orderId",  saved.getId());
                    c.put("customer", saved.getCustomerName());
                    c.put("total",    saved.getTotal());
                    collections.add(c);
                }
            } catch (Exception e) {
                errors.add(rowError("order", i, e));
            }
        }

        // ── Expenses ──────────────────────────────────────────────────────────
        for (int i = 0; i < expenses.size(); i++) {
            Map<String, Object> entry = expenses.get(i);
            try {
                LocalDate date = parseDate(entry.get("date"));
                requireNotFuture(date);   // a day that hasn't happened yet cannot hold records
                boolean recordingOnly = Boolean.TRUE.equals(entry.get("recordingOnly"));

                Expense saved = expenseController.createBackdatedExpense(
                        entry, userId, user.getFullName(), date, recordingOnly);
                affectedDates.add(date);

                // Cash-affecting iff not recording-only and paid in cash (reconcileExpenseCash
                // posts a cash-out only for CASH expenses; other methods net to zero).
                Object method = entry.get("paymentMethod");
                boolean cashAffecting = !recordingOnly
                        && method != null && "CASH".equalsIgnoreCase(method.toString().trim());
                if (cashAffecting && (earliestCashDate == null || date.isBefore(earliestCashDate))) {
                    earliestCashDate = date;
                }

                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("expenseId",     saved.getId());
                summary.put("date",          date.toString());
                summary.put("total",         saved.getTotalAmount());
                summary.put("lateImported",  saved.isLateImported());
                summary.put("recordingOnly", recordingOnly);
                committedExpenses.add(summary);
            } catch (Exception e) {
                errors.add(rowError("expense", i, e));
            }
        }

        // Recompute already-closed daily reports affected by this commit and mark them "amended"
        // (own-date refresh + cash cascade from the earliest cash-affecting date through today).
        List<String> amendedReports = List.of();
        if (!affectedDates.isEmpty()) {
            amendedReports = dailyReportService
                    .recomputeAffected(userId, user.getFullName(), affectedDates, earliestCashDate)
                    .stream().map(LocalDate::toString).toList();
        }

        // "Create daily report" checkbox: for each affected date that has no report yet, create
        // one now. closeForImportDate is idempotent (returns false if one already exists) and
        // also writes the per-day expense-log snapshot. Dates that already had a report were
        // refreshed by recomputeAffected above.
        List<String> createdReports = new ArrayList<>();
        if (createReport) {
            for (LocalDate d : affectedDates) {
                try {
                    if (dailyReportService.closeForImportDate(userId, user.getFullName(), d)) {
                        createdReports.add(d.toString());
                    }
                } catch (Exception e) {
                    errors.add(rowError("report", -1, e));
                }
            }
        }

        int committed = committedOrders.size() + committedExpenses.size();
        log.info("Backdated commit by {}: {} order(s), {} expense(s), {} error(s), dates {}, amended {}",
                user.getFullName(), committedOrders.size(), committedExpenses.size(),
                errors.size(), affectedDates, amendedReports);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("committed",         committed);
        result.put("committedOrders",   committedOrders);
        result.put("committedExpenses", committedExpenses);
        result.put("collections",       collections);
        result.put("errors",            errors);
        result.put("affectedDates",     affectedDates.stream().map(LocalDate::toString).toList());
        result.put("amendedReports",    amendedReports);
        result.put("createdReports",    createdReports);
        return ResponseEntity.ok(result);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asMapList(Object raw) {
        if (raw instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : list) if (o instanceof Map) out.add((Map<String, Object>) o);
            return out;
        }
        return List.of();
    }

    private static LocalDate parseDate(Object raw) {
        if (raw == null || raw.toString().isBlank())
            throw new IllegalArgumentException("date is required");
        try {
            return LocalDate.parse(raw.toString());
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid date: " + raw);
        }
    }

    /** Reject a business date that hasn't happened yet — records can never belong to a future day. */
    private static void requireNotFuture(LocalDate date) {
        if (date != null && date.isAfter(LocalDate.now()))
            throw new IllegalArgumentException("Cannot record entries for a future date (" + date + ")");
    }

    private static Map<String, Object> rowError(String type, int index, Exception e) {
        String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        // Previously a failed row was recorded only in the returned errors[] and the endpoint
        // still returned 200 — a total failure looked like a silent "committed 0". Log it too
        // so the real cause is visible server-side.
        log.warn("Backdated {} row {} failed: {}", type, index, reason, e);
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("type",   type);
        err.put("index",  index);
        err.put("reason", reason);
        return err;
    }
}
