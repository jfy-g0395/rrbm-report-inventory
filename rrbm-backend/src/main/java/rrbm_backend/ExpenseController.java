package rrbm_backend;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Expense management endpoints.
 *
 *  POST  /api/expenses                                           — Record a new expense
 *  GET   /api/expenses?date=YYYY-MM-DD                          — Get expenses for a date (default today)
 *  GET   /api/expenses/range?start=&end=                        — Get expenses in a date range
 *  GET   /api/expenses/summary                                  — Dashboard summary (today/yesterday/MTD/category)
 *  GET   /api/expenses/report/daily                             — Daily expense report
 *  GET   /api/expenses/report/weekly                            — Weekly expense report (SPEC §1.6)
 *  GET   /api/expenses/report/monthly                           — Monthly expense report
 *  GET   /api/expenses/export?start=&end=&format=&includeVoided= — Export (CSV / Excel / PDF)
 *  POST  /api/expenses/{id}/void                                — Void an expense entry
 */
@RestController
@RequestMapping("/api/expenses")
public class ExpenseController {

    private final ExpenseRepository            expenseRepository;
    private final UserRepository               userRepository;
    private final ActivityLogService           activityLogService;
    private final JwtUtil                      jwtUtil;
    private final SettingsRepository           settingsRepository;
    private final ExpenseCategoryRepository    categoryRepository;
    private final CashLedgerService            cashLedgerService;
    private final BCryptPasswordEncoder        passwordEncoder = new BCryptPasswordEncoder();

    public ExpenseController(ExpenseRepository expenseRepository,
                              UserRepository userRepository,
                              ActivityLogService activityLogService,
                              JwtUtil jwtUtil,
                              SettingsRepository settingsRepository,
                              ExpenseCategoryRepository categoryRepository,
                              CashLedgerService cashLedgerService) {
        this.expenseRepository  = expenseRepository;
        this.userRepository     = userRepository;
        this.activityLogService = activityLogService;
        this.jwtUtil            = jwtUtil;
        this.settingsRepository = settingsRepository;
        this.categoryRepository = categoryRepository;
        this.cashLedgerService  = cashLedgerService;
    }

    private Long userIdFromHeader(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return null;
        return jwtUtil.extractUserId(authHeader.substring(7));
    }

    // ── POST /api/expenses ─────────────────────────────────────────────────
    @PostMapping
    @Transactional
    public ResponseEntity<?> createExpense(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, Object> body) {

        Long adminId = userIdFromHeader(authHeader);
        if (adminId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }

        User admin = userRepository.findById(adminId).orElse(null);
        String adminName = admin != null ? admin.getFullName() : "Unknown";

        // Parse date (default to today)
        LocalDate date = LocalDate.now();
        if (body.containsKey("date") && body.get("date") != null) {
            try { date = LocalDate.parse(body.get("date").toString()); } catch (Exception ignored) {}
        }

        // Backdating window — read from settings, default 7 days
        int backdatingDays = settingsRepository.findById("expense_backdating_days")
                .map(s -> { try { return Integer.parseInt(s.getValue()); } catch (NumberFormatException e) { return 7; } })
                .orElse(7);
        if (date.isBefore(LocalDate.now().minusDays(backdatingDays))) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "Backdating beyond " + backdatingDays + " days is not allowed"));
        }

        // paymentMethod — required
        Object rawPm = body.get("paymentMethod");
        if (rawPm == null || rawPm.toString().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "paymentMethod is required"));
        }
        String paymentMethod = rawPm.toString().trim();

        // notes — optional; trim; cap at 500 chars
        String notes = null;
        if (body.containsKey("notes") && body.get("notes") != null) {
            String raw = body.get("notes").toString().trim();
            if (!raw.isEmpty()) notes = raw.length() > 500 ? raw.substring(0, 500) : raw;
        }

        // referenceNumber — optional
        String referenceNumber = null;
        if (body.containsKey("referenceNumber") && body.get("referenceNumber") != null) {
            String raw = body.get("referenceNumber").toString().trim();
            if (!raw.isEmpty()) referenceNumber = raw;
        }

        // Parse items
        Object rawItems = body.get("items");
        if (!(rawItems instanceof List)) {
            return ResponseEntity.badRequest().body(Map.of("error", "items must be a list"));
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> itemList = (List<Map<String, Object>>) rawItems;
        if (itemList.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "At least one item is required"));
        }

        // Validate categoryId on every non-blank item before touching the DB
        for (Map<String, Object> itemMap : itemList) {
            String desc = itemMap.getOrDefault("itemDescription", "").toString().trim();
            if (desc.isEmpty()) continue; // blank items are skipped below
            if (itemMap.get("categoryId") == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "categoryId is required for each item"));
            }
        }

        // Build Expense entity
        Expense expense = new Expense();
        expense.setDate(date);
        expense.setAdminId(adminId);
        expense.setAdminName(adminName);
        expense.setPaymentMethod(paymentMethod);
        expense.setNotes(notes);
        expense.setReferenceNumber(referenceNumber);

        for (Map<String, Object> itemMap : itemList) {
            String desc = itemMap.getOrDefault("itemDescription", "").toString().trim();
            if (desc.isEmpty()) continue;

            BigDecimal amount = BigDecimal.ZERO;
            try { amount = new BigDecimal(itemMap.getOrDefault("amount", "0").toString()); } catch (Exception ignored) {}

            Long categoryId = null;
            Object catId = itemMap.get("categoryId");
            if (catId instanceof Number n) {
                categoryId = n.longValue();
            } else if (catId != null) {
                try { categoryId = Long.parseLong(catId.toString()); } catch (Exception ignored) {}
            }

            ExpenseItem item = new ExpenseItem();
            item.setItemDescription(desc);
            item.setAmount(amount);
            item.setCategoryId(categoryId);
            item.setExpense(expense);
            expense.getItems().add(item);
        }

        if (expense.getItems().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No valid items provided"));
        }

        expense.recalculateTotal();
        Expense saved = expenseRepository.save(expense);

        // Activity log
        activityLogService.log(
                adminId, adminName,
                "EXPENSE_RECORDED",
                "Recorded expense of ₱" + saved.getTotalAmount() + " on " + saved.getDate()
                        + " with " + saved.getItems().size() + " item(s)",
                "EXPENSE", String.valueOf(saved.getId()));

        // Cash on hand: a cash-paid expense deducts from the drawer (net amount only).
        cashLedgerService.reconcileExpenseCash(saved, adminId, adminName);

        return ResponseEntity.ok(toMap(saved));
    }

    // ── PUT /api/expenses/{id} — edit an encoded expense (fix wrong inputs) ──
    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> updateExpense(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, Object> body) {

        Long adminId = userIdFromHeader(authHeader);
        if (adminId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }

        Expense expense = expenseRepository.findById(id).orElse(null);
        if (expense == null) {
            return ResponseEntity.status(404).body(Map.of("error", "Expense not found"));
        }
        if (expense.isVoided()) {
            return ResponseEntity.badRequest().body(Map.of("error", "A voided expense cannot be edited"));
        }

        User admin = userRepository.findById(adminId).orElse(null);
        String adminName = admin != null ? admin.getFullName() : "Unknown";

        // Date (optional) — only changed when supplied and parseable
        if (body.containsKey("date") && body.get("date") != null) {
            try { expense.setDate(LocalDate.parse(body.get("date").toString())); } catch (Exception ignored) {}
        }

        // paymentMethod — required
        Object rawPm = body.get("paymentMethod");
        if (rawPm == null || rawPm.toString().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "paymentMethod is required"));
        }
        expense.setPaymentMethod(rawPm.toString().trim());

        // notes — optional; trim; cap at 500 chars
        String notes = null;
        if (body.containsKey("notes") && body.get("notes") != null) {
            String raw = body.get("notes").toString().trim();
            if (!raw.isEmpty()) notes = raw.length() > 500 ? raw.substring(0, 500) : raw;
        }
        expense.setNotes(notes);

        // referenceNumber — optional
        String referenceNumber = null;
        if (body.containsKey("referenceNumber") && body.get("referenceNumber") != null) {
            String raw = body.get("referenceNumber").toString().trim();
            if (!raw.isEmpty()) referenceNumber = raw;
        }
        expense.setReferenceNumber(referenceNumber);

        // Parse items
        Object rawItems = body.get("items");
        if (!(rawItems instanceof List)) {
            return ResponseEntity.badRequest().body(Map.of("error", "items must be a list"));
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> itemList = (List<Map<String, Object>>) rawItems;
        if (itemList.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "At least one item is required"));
        }
        for (Map<String, Object> itemMap : itemList) {
            String desc = itemMap.getOrDefault("itemDescription", "").toString().trim();
            if (desc.isEmpty()) continue;
            if (itemMap.get("categoryId") == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "categoryId is required for each item"));
            }
        }

        // Replace items (orphanRemoval=true deletes the old rows within this transaction)
        List<ExpenseItem> newItems = new ArrayList<>();
        for (Map<String, Object> itemMap : itemList) {
            String desc = itemMap.getOrDefault("itemDescription", "").toString().trim();
            if (desc.isEmpty()) continue;

            BigDecimal amount = BigDecimal.ZERO;
            try { amount = new BigDecimal(itemMap.getOrDefault("amount", "0").toString()); } catch (Exception ignored) {}

            Long categoryId = null;
            Object catId = itemMap.get("categoryId");
            if (catId instanceof Number n) {
                categoryId = n.longValue();
            } else if (catId != null) {
                try { categoryId = Long.parseLong(catId.toString()); } catch (Exception ignored) {}
            }

            ExpenseItem item = new ExpenseItem();
            item.setItemDescription(desc);
            item.setAmount(amount);
            item.setCategoryId(categoryId);
            item.setExpense(expense);
            newItems.add(item);
        }
        if (newItems.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No valid items provided"));
        }
        expense.getItems().clear();
        expense.getItems().addAll(newItems);

        expense.recalculateTotal();
        Expense saved = expenseRepository.save(expense);

        activityLogService.log(
                adminId, adminName,
                "EXPENSE_EDITED",
                "Edited expense #" + saved.getId() + " — total now ₱" + saved.getTotalAmount()
                        + " on " + saved.getDate() + " with " + saved.getItems().size() + " item(s)",
                "EXPENSE", String.valueOf(saved.getId()));

        // Cash on hand: reconcile to the new amount/payment-method (writes only the delta;
        // correctly handles amount edits and CASH↔non-CASH switches).
        cashLedgerService.reconcileExpenseCash(saved, adminId, adminName);

        return ResponseEntity.ok(toMap(saved));
    }

    // ── GET /api/expenses?date=YYYY-MM-DD ──────────────────────────────────
    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> getExpensesByDate(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        LocalDate target = date != null ? date : LocalDate.now();
        List<Expense> list = expenseRepository.findByDateWithItems(target);
        return ResponseEntity.ok(list.stream().map(this::toMap).collect(Collectors.toList()));
    }

    // ── GET /api/expenses/range?start=YYYY-MM-DD&end=YYYY-MM-DD ───────────
    @GetMapping("/range")
    @Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> getExpensesByRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {

        List<Expense> list = expenseRepository.findByDateRangeWithItems(start, end);
        return ResponseEntity.ok(list.stream().map(this::toMap).collect(Collectors.toList()));
    }

    // ── GET /api/expenses/summary ──────────────────────────────────────────
    @GetMapping("/summary")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getSummary(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        if (userIdFromHeader(authHeader) == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }

        LocalDate today     = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);
        LocalDate monthStart = today.withDayOfMonth(1);

        BigDecimal todayTotal     = nullToZero(expenseRepository.sumNonVoidedForDate(today));
        long       todayCount     = expenseRepository.countNonVoidedForDate(today);
        BigDecimal yesterdayTotal = nullToZero(expenseRepository.sumNonVoidedForDate(yesterday));
        BigDecimal mtdTotal       = nullToZero(expenseRepository.sumNonVoidedForDateRange(monthStart, today));
        long       pendingVoids   = expenseRepository.countPendingVoids();

        Double vsYesterdayPct = null;
        if (yesterdayTotal.compareTo(BigDecimal.ZERO) != 0) {
            vsYesterdayPct = todayTotal.subtract(yesterdayTotal)
                    .divide(yesterdayTotal, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .doubleValue();
        }

        List<Object[]> rows = expenseRepository.sumByPrimaryCategoryForMonth(
                today.getYear(), today.getMonthValue());
        List<Map<String, Object>> mtdByCategory = new ArrayList<>();
        for (Object[] row : rows) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("categoryCode", row[0]);
            entry.put("categoryName", row[1]);
            entry.put("total",        row[2]);
            mtdByCategory.add(entry);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("todayTotal",       todayTotal);
        response.put("todayCount",       (int) todayCount);
        response.put("yesterdayTotal",   yesterdayTotal);
        response.put("vsYesterdayPct",   vsYesterdayPct);
        response.put("mtdTotal",         mtdTotal);
        response.put("mtdByCategory",    mtdByCategory);
        response.put("pendingVoidCount", (int) pendingVoids);

        return ResponseEntity.ok(response);
    }

    private static BigDecimal nullToZero(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    // ── GET /api/expenses/report/daily?date=YYYY-MM-DD&importedOnly=false ─────
    @GetMapping("/report/daily")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getDailyReport(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false, defaultValue = "false") boolean importedOnly) {

        if (userIdFromHeader(authHeader) == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }

        LocalDate target = date != null ? date : LocalDate.now();

        // Always fetch imported (non-voided) expenses so importedEntries is always present.
        List<Expense> importedExpenses = expenseRepository.findImportedByDateRange(target, target);
        List<Map<String, Object>> importedEntries = importedExpenses.stream().map(e -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id",          e.getId());
            entry.put("totalAmount", e.getTotalAmount());
            entry.put("importRef",   e.getImportRef());
            return entry;
        }).collect(Collectors.toList());

        BigDecimal total;
        long       count;
        List<Map<String, Object>> byCategory;
        List<Map<String, Object>> byPaymentMethod;

        if (importedOnly) {
            // Totals computed from the already-loaded importedExpenses list (no extra queries).
            total = importedExpenses.stream()
                    .map(e -> e.getTotalAmount() != null ? e.getTotalAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            count = importedExpenses.size();
            // Category / payment-method breakdowns are empty in U1 for the importedOnly view;
            // U2 can enrich these once real imported data exists.
            byCategory      = Collections.emptyList();
            byPaymentMethod = Collections.emptyList();
        } else {
            total = nullToZero(expenseRepository.sumNonVoidedForDate(target));
            count = expenseRepository.countNonVoidedForDate(target);

            List<Object[]> catRows = expenseRepository.sumByPrimaryCategoryForDate(target);
            byCategory = new ArrayList<>();
            for (Object[] row : catRows) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("categoryCode", row[0]);
                entry.put("categoryName", row[1]);
                entry.put("total",        row[2]);
                entry.put("entries",      ((Number) row[3]).intValue());
                byCategory.add(entry);
            }

            List<Object[]> pmRows = expenseRepository.sumByPaymentMethodForDate(target);
            byPaymentMethod = new ArrayList<>();
            for (Object[] row : pmRows) {
                BigDecimal methodTotal = (BigDecimal) row[1];
                Double pct = null;
                if (total.compareTo(BigDecimal.ZERO) != 0) {
                    pct = methodTotal.divide(total, 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100)).doubleValue();
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("method", row[0]);
                entry.put("total",  methodTotal);
                entry.put("pct",    pct);
                byPaymentMethod.add(entry);
            }
        }

        BigDecimal avgPerEntry = count > 0
                ? total.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP) : null;

        List<Expense> voidedExpenses = expenseRepository.findVoidedByDateRange(target, target);
        List<Map<String, Object>> voidedEntries = new ArrayList<>();
        for (Expense e : voidedExpenses) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id",          e.getId());
            entry.put("voidedAt",    e.getVoidedAt() != null ? e.getVoidedAt().toString() : null);
            entry.put("voidReason",  e.getVoidReason());
            entry.put("totalAmount", e.getTotalAmount());
            voidedEntries.add(entry);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("date",            target.toString());
        response.put("importedOnly",    importedOnly);
        response.put("total",           total);
        response.put("count",           (int) count);
        response.put("avgPerEntry",     avgPerEntry);
        response.put("byCategory",      byCategory);
        response.put("byPaymentMethod", byPaymentMethod);
        response.put("voidedEntries",   voidedEntries);
        response.put("importedEntries", importedEntries);

        return ResponseEntity.ok(response);
    }

    // ── GET /api/expenses/report/monthly?year=YYYY&month=M&importedOnly=false ──
    @GetMapping("/report/monthly")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getMonthlyReport(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false, defaultValue = "false") boolean importedOnly) {

        if (userIdFromHeader(authHeader) == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }

        LocalDate today = LocalDate.now();
        int targetYear  = year  != null ? year  : today.getYear();
        int targetMonth = month != null ? month : today.getMonthValue();

        LocalDate monthStart = LocalDate.of(targetYear, targetMonth, 1);
        LocalDate monthEnd   = monthStart.withDayOfMonth(monthStart.lengthOfMonth());

        // Always fetch imported expenses so importedEntries is always present.
        List<Expense> importedExpenses = expenseRepository.findImportedByDateRange(monthStart, monthEnd);
        List<Map<String, Object>> importedEntries = importedExpenses.stream().map(e -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id",          e.getId());
            entry.put("date",        e.getDate().toString());
            entry.put("totalAmount", e.getTotalAmount());
            entry.put("importRef",   e.getImportRef());
            return entry;
        }).collect(Collectors.toList());

        BigDecimal grandTotal;
        BigDecimal dailyAvg;
        Map<String, Object> highestDay;
        Map<String, Object> lowestDay;
        List<Map<String, Object>> byCategory;
        List<Map<String, Object>> dailyBreakdown;

        if (importedOnly) {
            // Compute from imported set in Java — group by date.
            Map<LocalDate, BigDecimal> importedByDay = new TreeMap<>();
            for (Expense e : importedExpenses) {
                importedByDay.merge(e.getDate(),
                        e.getTotalAmount() != null ? e.getTotalAmount() : BigDecimal.ZERO,
                        BigDecimal::add);
            }

            grandTotal = importedByDay.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);

            long daysWithImported = importedByDay.values().stream()
                    .filter(v -> v.compareTo(BigDecimal.ZERO) > 0).count();
            dailyAvg = daysWithImported > 0
                    ? grandTotal.divide(BigDecimal.valueOf(daysWithImported), 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

            highestDay = null;
            lowestDay  = null;
            BigDecimal maxAmt = null;
            BigDecimal minAmt = null;
            for (Map.Entry<LocalDate, BigDecimal> entry : importedByDay.entrySet()) {
                BigDecimal v = entry.getValue();
                if (v.compareTo(BigDecimal.ZERO) > 0) {
                    if (maxAmt == null || v.compareTo(maxAmt) > 0) {
                        maxAmt     = v;
                        highestDay = new LinkedHashMap<>(Map.of("date", entry.getKey().toString(), "total", v));
                    }
                    if (minAmt == null || v.compareTo(minAmt) < 0) {
                        minAmt    = v;
                        lowestDay = new LinkedHashMap<>(Map.of("date", entry.getKey().toString(), "total", v));
                    }
                }
            }

            byCategory = Collections.emptyList(); // enriched in U2 when real imported data exists

            // Calendar-complete daily breakdown for the month
            dailyBreakdown = new ArrayList<>();
            LocalDate cursor = monthStart;
            while (!cursor.isAfter(monthEnd)) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("date",  cursor.toString());
                BigDecimal dayTotal = importedByDay.getOrDefault(cursor, BigDecimal.ZERO);
                entry.put("total", dayTotal);
                entry.put("count", 0); // per-day count not tracked in Java path for U1
                dailyBreakdown.add(entry);
                cursor = cursor.plusDays(1);
            }
        } else {
            grandTotal = nullToZero(expenseRepository.sumNonVoidedForDateRange(monthStart, monthEnd));

            List<Object[]> dayRows = expenseRepository.sumByDayForDateRange(monthStart, monthEnd);
            Map<LocalDate, Object[]> dayMap = new LinkedHashMap<>();
            for (Object[] row : dayRows) dayMap.put((LocalDate) row[0], row);

            dailyBreakdown = new ArrayList<>();
            LocalDate cursor = monthStart;
            while (!cursor.isAfter(monthEnd)) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("date", cursor.toString());
                if (dayMap.containsKey(cursor)) {
                    Object[] row = dayMap.get(cursor);
                    entry.put("total", row[1]);
                    entry.put("count", ((Number) row[2]).intValue());
                } else {
                    entry.put("total", BigDecimal.ZERO);
                    entry.put("count", 0);
                }
                dailyBreakdown.add(entry);
                cursor = cursor.plusDays(1);
            }

            long daysWithData = dayRows.size();
            dailyAvg = daysWithData > 0
                    ? grandTotal.divide(BigDecimal.valueOf(daysWithData), 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

            highestDay = null;
            lowestDay  = null;
            BigDecimal maxAmt = null;
            BigDecimal minAmt = null;
            for (Object[] row : dayRows) {
                BigDecimal dayTotal = (BigDecimal) row[1];
                if (dayTotal.compareTo(BigDecimal.ZERO) > 0) {
                    if (maxAmt == null || dayTotal.compareTo(maxAmt) > 0) {
                        maxAmt     = dayTotal;
                        highestDay = new LinkedHashMap<>(Map.of("date", row[0].toString(), "total", dayTotal));
                    }
                    if (minAmt == null || dayTotal.compareTo(minAmt) < 0) {
                        minAmt    = dayTotal;
                        lowestDay = new LinkedHashMap<>(Map.of("date", row[0].toString(), "total", dayTotal));
                    }
                }
            }

            List<Object[]> catRows = expenseRepository.sumByPrimaryCategoryForMonthWithCount(targetYear, targetMonth);
            byCategory = new ArrayList<>();
            for (Object[] row : catRows) {
                BigDecimal catTotal = (BigDecimal) row[2];
                Double pct = null;
                if (grandTotal.compareTo(BigDecimal.ZERO) != 0) {
                    pct = catTotal.divide(grandTotal, 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100)).doubleValue();
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("categoryCode", row[0]);
                entry.put("categoryName", row[1]);
                entry.put("total",        catTotal);
                entry.put("pct",          pct);
                byCategory.add(entry);
            }
        }

        List<Expense> voidedExpenses = expenseRepository.findVoidedByDateRange(monthStart, monthEnd);
        List<Map<String, Object>> voidedEntries = new ArrayList<>();
        for (Expense e : voidedExpenses) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id",          e.getId());
            entry.put("date",        e.getDate().toString());
            entry.put("voidedAt",    e.getVoidedAt() != null ? e.getVoidedAt().toString() : null);
            entry.put("voidReason",  e.getVoidReason());
            entry.put("totalAmount", e.getTotalAmount());
            voidedEntries.add(entry);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("year",           targetYear);
        response.put("month",          targetMonth);
        response.put("importedOnly",   importedOnly);
        response.put("grandTotal",     grandTotal);
        response.put("dailyAvg",       dailyAvg);
        response.put("highestDay",     highestDay);
        response.put("lowestDay",      lowestDay);
        response.put("byCategory",     byCategory);
        response.put("dailyBreakdown", dailyBreakdown);
        response.put("voidedEntries",  voidedEntries);
        response.put("importedEntries", importedEntries);

        return ResponseEntity.ok(response);
    }

    // ── GET /api/expenses/report/weekly?year=YYYY&week=N ──────────────────
    @GetMapping("/report/weekly")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getWeeklyReport(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer week) {

        if (userIdFromHeader(authHeader) == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }

        LocalDate today = LocalDate.now();
        int targetYear = year != null ? year : today.get(IsoFields.WEEK_BASED_YEAR);
        int targetWeek = week != null ? week : today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);

        // Jan 4 is always in ISO week 1; previousOrSame(MONDAY) gives that week's Monday.
        LocalDate week1Mon = LocalDate.of(targetYear, 1, 4)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate weekStart = week1Mon.plusWeeks(targetWeek - 1);
        LocalDate weekEnd   = weekStart.plusDays(6);

        // Grand total and week-over-week %
        BigDecimal grandTotal = nullToZero(expenseRepository.sumNonVoidedForDateRange(weekStart, weekEnd));
        BigDecimal prevTotal  = nullToZero(
                expenseRepository.sumNonVoidedForDateRange(weekStart.minusWeeks(1), weekEnd.minusWeeks(1)));
        Double weekOverWeek = prevTotal.compareTo(BigDecimal.ZERO) == 0 ? null
                : grandTotal.subtract(prevTotal)
                        .divide(prevTotal, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100)).doubleValue();

        // Per-day DB rows; fill all 7 days Mon–Sun
        List<Object[]> dayRows = expenseRepository.sumByDayForDateRange(weekStart, weekEnd);
        Map<LocalDate, Object[]> dayMap = new LinkedHashMap<>();
        for (Object[] row : dayRows) dayMap.put((LocalDate) row[0], row);

        List<Map<String, Object>> dayByDay = new ArrayList<>();
        LocalDate cursor = weekStart;
        while (!cursor.isAfter(weekEnd)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("date", cursor.toString());
            if (dayMap.containsKey(cursor)) {
                Object[] row = dayMap.get(cursor);
                entry.put("total", row[1]);
                entry.put("count", ((Number) row[2]).intValue());
            } else {
                entry.put("total", BigDecimal.ZERO);
                entry.put("count", 0);
            }
            dayByDay.add(entry);
            cursor = cursor.plusDays(1);
        }

        // Daily average over days that had spend
        long daysWithData = dayRows.size();
        BigDecimal dailyAvg = daysWithData > 0
                ? grandTotal.divide(BigDecimal.valueOf(daysWithData), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        // Highest / lowest day (only days with > 0 spend)
        Map<String, Object> highestDay = null;
        Map<String, Object> lowestDay  = null;
        BigDecimal maxAmt = null;
        BigDecimal minAmt = null;
        for (Object[] row : dayRows) {
            BigDecimal dayTotal = (BigDecimal) row[1];
            if (dayTotal.compareTo(BigDecimal.ZERO) > 0) {
                if (maxAmt == null || dayTotal.compareTo(maxAmt) > 0) {
                    maxAmt     = dayTotal;
                    highestDay = new LinkedHashMap<>(Map.of("date", row[0].toString(), "total", dayTotal));
                }
                if (minAmt == null || dayTotal.compareTo(minAmt) < 0) {
                    minAmt    = dayTotal;
                    lowestDay = new LinkedHashMap<>(Map.of("date", row[0].toString(), "total", dayTotal));
                }
            }
        }

        // Category breakdown with pct-of-total
        List<Object[]> catRows = expenseRepository.sumByPrimaryCategoryForDateRange(weekStart, weekEnd);
        List<Map<String, Object>> byCategory = new ArrayList<>();
        for (Object[] row : catRows) {
            BigDecimal catTotal = (BigDecimal) row[2];
            Double pct = null;
            if (grandTotal.compareTo(BigDecimal.ZERO) != 0) {
                pct = catTotal.divide(grandTotal, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100)).doubleValue();
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("categoryCode", row[0]);
            entry.put("categoryName", row[1]);
            entry.put("total",        catTotal);
            entry.put("pct",          pct);
            byCategory.add(entry);
        }

        // Voided entries for the week
        List<Expense> voidedExpenses = expenseRepository.findVoidedByDateRange(weekStart, weekEnd);
        List<Map<String, Object>> voidedEntries = new ArrayList<>();
        for (Expense e : voidedExpenses) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id",          e.getId());
            entry.put("date",        e.getDate().toString());
            entry.put("voidedAt",    e.getVoidedAt() != null ? e.getVoidedAt().toString() : null);
            entry.put("voidReason",  e.getVoidReason());
            entry.put("totalAmount", e.getTotalAmount());
            voidedEntries.add(entry);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("year",         targetYear);
        response.put("week",         targetWeek);
        response.put("weekStart",    weekStart.toString());
        response.put("weekEnd",      weekEnd.toString());
        response.put("grandTotal",   grandTotal);
        response.put("dailyAvg",     dailyAvg);
        response.put("weekOverWeek", weekOverWeek);
        response.put("highestDay",   highestDay);
        response.put("lowestDay",    lowestDay);
        response.put("dayByDay",     dayByDay);
        response.put("byCategory",   byCategory);
        response.put("voidedEntries", voidedEntries);

        return ResponseEntity.ok(response);
    }

    // ── POST /api/expenses/{id}/void ───────────────────────────────────────
    @PostMapping("/{id}/void")
    @Transactional
    public ResponseEntity<?> voidExpense(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, String> body) {

        Long adminId = userIdFromHeader(authHeader);
        if (adminId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }

        Expense expense = expenseRepository.findById(id).orElse(null);
        if (expense == null) {
            return ResponseEntity.status(404).body(Map.of("error", "Expense not found"));
        }

        if (expense.isVoided()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Expense is already voided"));
        }

        User caller = userRepository.findById(adminId).orElse(null);
        if (caller == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));
        }

        String rawKey = body.getOrDefault("adminSecurityKey", "");
        if (caller.getAdminSecurityKey() == null
                || !passwordEncoder.matches(rawKey != null ? rawKey.trim() : "", caller.getAdminSecurityKey())) {
            return ResponseEntity.status(403).body(Map.of("error", "Invalid security key"));
        }

        String voidReason = body.getOrDefault("voidReason", "").trim();
        expense.setVoided(true);
        expense.setVoidedAt(OffsetDateTime.now());
        expense.setVoidedBy(adminId);
        expense.setVoidReason(voidReason);
        expense.setStatus("VOIDED");

        Expense saved = expenseRepository.save(expense);

        activityLogService.log(
                adminId, caller.getFullName(),
                "EXPENSE_VOIDED",
                "Voided expense #" + saved.getId() + ". Reason: " + voidReason,
                "EXPENSE", String.valueOf(saved.getId()));

        // Cash on hand: voiding a cash expense restores the cash to the drawer.
        cashLedgerService.reconcileExpenseCash(saved, adminId, caller.getFullName());

        return ResponseEntity.ok(toMap(saved));
    }

    // ── GET /api/expenses/export?start=&end=&format=&includeVoided= ───────
    @GetMapping("/export")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> exportExpenses(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
            @RequestParam(required = false, defaultValue = "csv") String format,
            @RequestParam(required = false, defaultValue = "false") boolean includeVoided) {

        List<Expense> all = expenseRepository.findByDateRangeForExport(start, end);
        if (!includeVoided) {
            all = all.stream()
                    .filter(e -> !e.isVoided() && !"VOIDED".equals(e.getStatus()))
                    .collect(Collectors.toList());
        }

        Map<Long, String> catCodeMap = buildCatCodeMap(all);

        byte[]  content;
        String  contentType;
        String  fileName;

        if ("excel".equalsIgnoreCase(format)) {
            content     = buildExcelHtml(all, catCodeMap, start, end).getBytes(StandardCharsets.UTF_8);
            contentType = "application/vnd.ms-excel";
            fileName    = "expenses-" + start + "-to-" + end + ".xls";
        } else if ("pdf".equalsIgnoreCase(format)) {
            content     = buildPdfHtml(all, catCodeMap, start, end).getBytes(StandardCharsets.UTF_8);
            contentType = "text/html; charset=UTF-8";
            fileName    = null; // PDF opens inline for browser-print; no forced download
        } else { // csv (default)
            content     = buildCsv(all, catCodeMap).getBytes(StandardCharsets.UTF_8);
            contentType = "text/csv; charset=UTF-8";
            fileName    = "expenses-" + start + "-to-" + end + ".csv";
        }

        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, contentType);
        if (fileName != null) {
            builder = builder.header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + fileName + "\"");
        }
        return builder.body(content);
    }

    // ── Export helpers ────────────────────────────────────────────────────────

    /**
     * Builds a categoryId → primary-category-code map for all items in the list.
     * Sub-categories are walked up one level to their parent's code.
     */
    private Map<Long, String> buildCatCodeMap(List<Expense> expenses) {
        Set<Long> catIds = expenses.stream()
                .flatMap(e -> e.getItems().stream())
                .map(ExpenseItem::getCategoryId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        if (catIds.isEmpty()) return Collections.emptyMap();

        Map<Long, ExpenseCategory> catMap = new HashMap<>();
        categoryRepository.findAllById(catIds).forEach(c -> catMap.put(c.getId(), c));

        // Fetch parent categories for sub-categories not already in the map
        Set<Long> parentIds = catMap.values().stream()
                .filter(c -> c.getParentId() != null)
                .map(ExpenseCategory::getParentId)
                .filter(pid -> !catMap.containsKey(pid))
                .collect(Collectors.toSet());
        if (!parentIds.isEmpty()) {
            categoryRepository.findAllById(parentIds).forEach(c -> catMap.put(c.getId(), c));
        }

        Map<Long, String> codeMap = new HashMap<>();
        for (Map.Entry<Long, ExpenseCategory> entry : catMap.entrySet()) {
            ExpenseCategory cat = entry.getValue();
            if (cat.getParentId() == null) {
                codeMap.put(entry.getKey(), cat.getCode() != null ? cat.getCode() : cat.getName());
            } else {
                ExpenseCategory parent = catMap.get(cat.getParentId());
                codeMap.put(entry.getKey(),
                        parent != null && parent.getCode() != null ? parent.getCode() : cat.getName());
            }
        }
        return codeMap;
    }

    private String buildCsv(List<Expense> expenses, Map<Long, String> catCodeMap) {
        StringBuilder sb = new StringBuilder();
        sb.append("id,date,paymentMethod,referenceNumber,status,totalAmount,notes,categoryCode,itemDescription,itemAmount\n");
        for (Expense e : expenses) {
            for (ExpenseItem item : e.getItems()) {
                String catCode = item.getCategoryId() != null
                        ? catCodeMap.getOrDefault(item.getCategoryId(), "") : "";
                sb.append(csvField(String.valueOf(e.getId()))).append(',');
                sb.append(csvField(e.getDate().toString())).append(',');
                sb.append(csvField(e.getPaymentMethod())).append(',');
                sb.append(csvField(e.getReferenceNumber())).append(',');
                sb.append(csvField(e.getStatus())).append(',');
                sb.append(csvField(e.getTotalAmount() != null ? e.getTotalAmount().toPlainString() : "0")).append(',');
                sb.append(csvField(e.getNotes())).append(',');
                sb.append(csvField(catCode)).append(',');
                sb.append(csvField(item.getItemDescription())).append(',');
                sb.append(csvField(item.getAmount() != null ? item.getAmount().toPlainString() : "0")).append('\n');
            }
        }
        return sb.toString();
    }

    private String buildExcelHtml(List<Expense> expenses, Map<Long, String> catCodeMap,
                                   LocalDate start, LocalDate end) {
        StringBuilder sb = new StringBuilder();
        sb.append("<html><head><meta charset=\"UTF-8\">");
        sb.append("<style>table{border-collapse:collapse;}th,td{border:1px solid #999;padding:4px 8px;font-size:12px;}th{background:#f0f0f0;}</style>");
        sb.append("</head><body>");
        sb.append("<h3>RRBM Expense Export: ").append(start).append(" to ").append(end).append("</h3>");
        sb.append("<table><thead><tr>");
        for (String col : new String[]{"ID", "Date", "Payment Method", "Reference Number",
                "Status", "Total Amount", "Notes", "Category Code", "Item Description", "Item Amount"}) {
            sb.append("<th>").append(col).append("</th>");
        }
        sb.append("</tr></thead><tbody>");
        for (Expense e : expenses) {
            for (ExpenseItem item : e.getItems()) {
                String catCode = item.getCategoryId() != null
                        ? catCodeMap.getOrDefault(item.getCategoryId(), "") : "";
                sb.append("<tr>");
                appendTd(sb, String.valueOf(e.getId()));
                appendTd(sb, e.getDate().toString());
                appendTd(sb, e.getPaymentMethod());
                appendTd(sb, e.getReferenceNumber());
                appendTd(sb, e.getStatus());
                appendTd(sb, e.getTotalAmount() != null ? e.getTotalAmount().toPlainString() : "0");
                appendTd(sb, e.getNotes());
                appendTd(sb, catCode);
                appendTd(sb, item.getItemDescription());
                appendTd(sb, item.getAmount() != null ? item.getAmount().toPlainString() : "0");
                sb.append("</tr>");
            }
        }
        sb.append("</tbody></table></body></html>");
        return sb.toString();
    }

    private String buildPdfHtml(List<Expense> expenses, Map<Long, String> catCodeMap,
                                 LocalDate start, LocalDate end) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\">");
        sb.append("<title>Expense Export ").append(start).append(" to ").append(end).append("</title>");
        sb.append("<style>");
        sb.append("body{font-family:Arial,sans-serif;font-size:12px;padding:20px;color:#1A1208;max-width:960px;margin:0 auto;}");
        sb.append(".header{display:flex;align-items:center;gap:14px;border-bottom:3px solid #FAD16A;padding-bottom:12px;margin-bottom:16px;}");
        sb.append(".logo{width:40px;height:40px;background:#FAD16A;border-radius:6px;display:flex;align-items:center;justify-content:center;font-weight:900;font-size:18px;color:#2C1A0E;flex-shrink:0;}");
        sb.append("table{width:100%;border-collapse:collapse;margin-bottom:16px;}");
        sb.append("th{background:#FAD16A;padding:6px 8px;text-align:left;font-size:11px;text-transform:uppercase;}");
        sb.append("td{padding:5px 8px;border-bottom:1px solid #eee;font-size:11px;}");
        sb.append(".footer{margin-top:24px;font-size:10px;color:#999;text-align:center;border-top:1px solid #eee;padding-top:8px;}");
        sb.append("@media print{body{padding:10px;}}");
        sb.append("</style></head><body>");
        sb.append("<div class=\"header\">");
        sb.append("<div class=\"logo\">R</div>");
        sb.append("<div><div style=\"font-size:15px;font-weight:700;color:#2C1A0E;\">RRBM Packaging Supplies and Trading</div>");
        sb.append("<div style=\"font-size:11px;color:#666;\">Expense Export &nbsp;|&nbsp; ")
          .append(start).append(" to ").append(end).append("</div></div></div>");
        sb.append("<table><thead><tr>");
        for (String col : new String[]{"ID", "Date", "Payment Method", "Ref. No.",
                "Status", "Total", "Notes", "Category", "Item Description", "Item Amount"}) {
            sb.append("<th>").append(col).append("</th>");
        }
        sb.append("</tr></thead><tbody>");
        for (Expense e : expenses) {
            for (ExpenseItem item : e.getItems()) {
                String catCode = item.getCategoryId() != null
                        ? catCodeMap.getOrDefault(item.getCategoryId(), "") : "";
                sb.append("<tr>");
                appendTd(sb, String.valueOf(e.getId()));
                appendTd(sb, e.getDate().toString());
                appendTd(sb, e.getPaymentMethod());
                appendTd(sb, e.getReferenceNumber());
                appendTd(sb, e.getStatus());
                appendTd(sb, e.getTotalAmount() != null ? e.getTotalAmount().toPlainString() : "0");
                appendTd(sb, e.getNotes());
                appendTd(sb, catCode);
                appendTd(sb, item.getItemDescription());
                appendTd(sb, item.getAmount() != null ? item.getAmount().toPlainString() : "0");
                sb.append("</tr>");
            }
        }
        sb.append("</tbody></table>");
        sb.append("<div class=\"footer\">RRBM Management System &middot; Confidential &middot; Internal use only</div>");
        // Auto-trigger browser print dialog
        sb.append("<script>window.onload=function(){window.print();};<\\/script>");
        sb.append("</body></html>");
        return sb.toString();
    }

    /** Appends a single HTML table cell with its content HTML-escaped. */
    private static void appendTd(StringBuilder sb, String value) {
        sb.append("<td>").append(htmlEscape(value)).append("</td>");
    }

    /** Wraps a CSV field value in double-quotes if it contains delimiters or quotes. */
    private static String csvField(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    /** Minimal HTML escaping for data rendered inside HTML tags. */
    private static String htmlEscape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;");
    }

    // ── Serialise Expense → Map ─────────────────────────────────────────────
    private Map<String, Object> toMap(Expense e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",              e.getId());
        m.put("date",            e.getDate().toString());
        m.put("adminId",         e.getAdminId());
        m.put("adminName",       e.getAdminName());
        m.put("totalAmount",     e.getTotalAmount());
        m.put("paymentMethod",   e.getPaymentMethod());
        m.put("notes",           e.getNotes());
        m.put("referenceNumber", e.getReferenceNumber());
        m.put("status",          e.getStatus());
        m.put("voided",          e.isVoided());
        m.put("voidedAt",        e.getVoidedAt() != null ? e.getVoidedAt().toString() : null);
        m.put("voidedBy",        e.getVoidedBy());
        m.put("voidReason",      e.getVoidReason());
        m.put("isImported",      e.isImported());
        m.put("importRef",       e.getImportRef());
        m.put("lateImported",    e.isLateImported());
        m.put("createdAt",       e.getCreatedAt().toString());
        m.put("items", e.getItems().stream().map(i -> {
            Map<String, Object> im = new LinkedHashMap<>();
            im.put("id",              i.getId());
            im.put("itemDescription", i.getItemDescription());
            im.put("amount",          i.getAmount());
            im.put("categoryId",      i.getCategoryId());
            return im;
        }).collect(Collectors.toList()));
        return m;
    }
}
