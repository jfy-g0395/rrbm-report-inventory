package rrbm_backend;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Reporting endpoints:
 *   GET /api/reports/insights-summary?month=YYYY-MM   — monthly order analytics (+ MoM + expenses)
 *   GET /api/reports/accounting-summary?month=YYYY-MM — transaction-ledger monthly accounting
 *   GET /api/reports/source-breakdown?month=YYYY-MM   — orders by source channel
 *   GET /api/reports/top-agents?month=YYYY-MM         — top 10 agents/resellers by revenue
 *   GET /api/reports/top-dates?month=YYYY-MM          — top 3 highest-revenue dates
 *   GET /api/reports/pizza-summary?month=YYYY-MM      — pizza box qty breakdown
 *   GET /api/reports/hot-selling?month=YYYY-MM        — top 10 HOT/SELLING tagged items
 *   GET /api/reports/delivery-fees?month=YYYY-MM      — orders with delivery fee > 0
 *   GET /api/reports/expense-breakdown?month=YYYY-MM  — expense items grouped by description
 */
@RestController
@RequestMapping("/api/reports")
public class ReportsController {

    private final OrderRepository        orderRepository;
    private final TransactionService     transactionService;
    private final DailyReportRepository  dailyReportRepository;
    private final UserRepository         userRepository;
    private final ExpenseRepository      expenseRepository;

    @PersistenceContext
    private EntityManager entityManager;

    public ReportsController(OrderRepository orderRepository,
                             TransactionService transactionService,
                             DailyReportRepository dailyReportRepository,
                             UserRepository userRepository,
                             ExpenseRepository expenseRepository) {
        this.orderRepository       = orderRepository;
        this.transactionService    = transactionService;
        this.dailyReportRepository = dailyReportRepository;
        this.userRepository        = userRepository;
        this.expenseRepository     = expenseRepository;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helper: parse month param (YYYY-MM), default to current month
    // ──────────────────────────────────────────────────────────────────────────
    private YearMonth parseMonth(String month) {
        if (month == null || month.isBlank()) return YearMonth.now();
        return YearMonth.parse(month);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // GET /api/reports/insights-summary?month=YYYY-MM
    // ──────────────────────────────────────────────────────────────────────────
    @GetMapping("/insights-summary")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getInsightsSummary(@RequestParam(defaultValue = "") String month) {

        YearMonth ym;
        try {
            ym = parseMonth(month);
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid month format. Use YYYY-MM."));
        }

        LocalDate start = ym.atDay(1);
        LocalDate end   = ym.atEndOfMonth();

        // ── Current month orders ───────────────────────────────────────────
        List<Order> allOrders = orderRepository.findByDateRangeWithItems(start, end);
        // M-31 fix 1: exclude PENDING_COLLECTION — those orders have a deferralVoid in
        // the ledger (net = ₱0) so they must not be counted as billed revenue or sold items.
        List<Order> billed = allOrders.stream()
                .filter(o -> !"CANCELLED".equals(o.getStatus())
                          && !"PENDING_COLLECTION".equals(o.getStatus())
                          && !"SCHEDULED_DELIVERY".equals(o.getStatus()))
                .collect(Collectors.toList());

        long totalOrders = billed.size();
        // M-31 fix 2: compute revenue from the transaction ledger instead of order.total.
        // order.total never changes after creation — refunds and voids are invisible to it.
        // The ledger stores SALE as positive and REFUND/VOID as negative; summing all
        // entries gives correct net revenue, matching accounting-summary for the same period.
        List<Transaction> txns = transactionService.getByDateRange(start, end);
        BigDecimal totalRevenue = txns.stream()
                .map(Transaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long totalItemsSold = billed.stream()
                .flatMap(o -> o.getItems().stream())
                .mapToLong(item -> item.getQuantity() != null ? item.getQuantity() : 0)
                .sum();

        // ── Daily breakdown ────────────────────────────────────────────────
        Map<LocalDate, List<Order>> byDate = billed.stream()
                .collect(Collectors.groupingBy(o -> o.getCreatedAt().toLocalDate()));

        List<Map<String, Object>> dailyBreakdown = byDate.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> {
                    LocalDate date      = entry.getKey();
                    List<Order> dayOrders = entry.getValue();
                    BigDecimal dayRevenue = dayOrders.stream()
                            .map(o -> o.getTotal() != null ? o.getTotal() : BigDecimal.ZERO)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("date",    date.toString());
                    row.put("orders",  dayOrders.size());
                    row.put("revenue", dayRevenue);
                    return row;
                })
                .collect(Collectors.toList());

        // ── Top 5 products by quantity ─────────────────────────────────────
        Map<String, BigDecimal[]> productRevMap = new LinkedHashMap<>();
        billed.stream()
                .flatMap(o -> o.getItems().stream())
                .forEach(item -> {
                    String name    = item.getProductName() != null ? item.getProductName() : "Unknown";
                    long qty       = item.getQuantity() != null ? item.getQuantity() : 0;
                    BigDecimal rev = item.getSubtotal() != null ? item.getSubtotal() : BigDecimal.ZERO;
                    productRevMap.computeIfAbsent(name, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                    BigDecimal[] agg = productRevMap.get(name);
                    agg[0] = agg[0].add(BigDecimal.valueOf(qty));
                    agg[1] = agg[1].add(rev);
                });

        BigDecimal grandQty = productRevMap.values().stream()
                .map(a -> a[0]).reduce(BigDecimal.ZERO, BigDecimal::add);

        List<Map<String, Object>> topProducts = productRevMap.entrySet().stream()
                .sorted((a, b) -> b.getValue()[0].compareTo(a.getValue()[0]))
                .limit(10)
                .map(entry -> {
                    String name    = entry.getKey();
                    BigDecimal qty = entry.getValue()[0];
                    BigDecimal rev = entry.getValue()[1];
                    BigDecimal pct = grandQty.compareTo(BigDecimal.ZERO) == 0
                            ? BigDecimal.ZERO
                            : qty.multiply(BigDecimal.valueOf(100))
                                 .divide(grandQty, 1, RoundingMode.HALF_UP);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name",    name);
                    row.put("qty",     qty.longValue());
                    row.put("revenue", rev);
                    row.put("pct",     pct);
                    return row;
                })
                .collect(Collectors.toList());

        for (int i = 0; i < topProducts.size(); i++) {
            topProducts.get(i).put("rank", i + 1);
        }

        // ── Previous month comparison ──────────────────────────────────────
        YearMonth prevYm    = ym.minusMonths(1);
        LocalDate prevStart = prevYm.atDay(1);
        LocalDate prevEnd   = prevYm.atEndOfMonth();

        // M-31: same two fixes as current month — exclude PENDING_COLLECTION, use ledger for revenue.
        List<Order> prevOrders = orderRepository.findByDateRangeWithItems(prevStart, prevEnd)
                .stream()
                .filter(o -> !"CANCELLED".equals(o.getStatus())
                          && !"PENDING_COLLECTION".equals(o.getStatus())
                          && !"SCHEDULED_DELIVERY".equals(o.getStatus()))
                .collect(Collectors.toList());

        long       prevMonthOrders  = prevOrders.size();
        List<Transaction> prevTxns  = transactionService.getByDateRange(prevStart, prevEnd);
        BigDecimal prevMonthRevenue = prevTxns.stream()
                .map(Transaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // ── Total expenses for current month ───────────────────────────────
        @SuppressWarnings("unchecked")
        List<Object> expResult = entityManager.createNativeQuery(
                "SELECT COALESCE(SUM(total_amount), 0) FROM expenses " +
                "WHERE date >= :startDate AND date <= :endDate")
                .setParameter("startDate", start)
                .setParameter("endDate",   end)
                .getResultList();

        BigDecimal totalExpenses = expResult.isEmpty() || expResult.get(0) == null
                ? BigDecimal.ZERO
                : (BigDecimal) expResult.get(0);

        // ── Daily expense breakdown (for sales-vs-expenses chart) ──────────
        @SuppressWarnings("unchecked")
        List<Object[]> dailyExpRows = entityManager.createNativeQuery(
                "SELECT date, COALESCE(SUM(total_amount), 0) as daily_total " +
                "FROM expenses " +
                "WHERE date >= :startDate AND date <= :endDate " +
                "GROUP BY date ORDER BY date")
                .setParameter("startDate", start)
                .setParameter("endDate",   end)
                .getResultList();

        List<Map<String, Object>> dailyExpenses = dailyExpRows.stream().map(r -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date",   r[0] != null ? r[0].toString() : "");
            row.put("amount", r[1] != null ? (BigDecimal) r[1] : BigDecimal.ZERO);
            return row;
        }).collect(Collectors.toList());

        // ── Assemble response ──────────────────────────────────────────────
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("month",            ym.toString());
        result.put("totalOrders",      totalOrders);
        result.put("totalRevenue",     totalRevenue);
        result.put("totalItemsSold",   totalItemsSold);
        result.put("totalExpenses",    totalExpenses);
        result.put("prevMonth",        prevYm.toString());
        result.put("prevMonthOrders",  prevMonthOrders);
        result.put("prevMonthRevenue", prevMonthRevenue);
        result.put("dailyBreakdown",   dailyBreakdown);
        result.put("dailyExpenses",    dailyExpenses);
        result.put("topProducts",      topProducts);

        return ResponseEntity.ok(result);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // GET /api/reports/accounting-summary?month=YYYY-MM
    // ──────────────────────────────────────────────────────────────────────────
    @GetMapping("/accounting-summary")
    public ResponseEntity<?> getAccountingSummary(
            @RequestParam(defaultValue = "") String month) {

        YearMonth ym;
        try {
            ym = parseMonth(month);
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", "Invalid month format. Use YYYY-MM."));
        }

        LocalDate start = ym.atDay(1);
        LocalDate end   = ym.atEndOfMonth();

        List<Transaction> allTxns = transactionService.getByDateRange(start, end);

        BigDecimal grossSales  = BigDecimal.ZERO;
        BigDecimal refunds     = BigDecimal.ZERO;
        BigDecimal adjustments = BigDecimal.ZERO;

        for (Transaction t : allTxns) {
            switch (t.getTransactionType()) {
                case "SALE"                  -> grossSales  = grossSales.add(t.getAmount());
                case "REFUND","VOID","RETURN" -> refunds    = refunds.add(t.getAmount());
                case "ADJUSTMENT"            -> adjustments = adjustments.add(t.getAmount());
            }
        }

        BigDecimal netSales = grossSales.add(refunds).add(adjustments);

        Map<LocalDate, BigDecimal[]> byDate = new TreeMap<>();
        for (Transaction t : allTxns) {
            LocalDate d = t.getEffectiveDate();
            byDate.computeIfAbsent(d, k -> new BigDecimal[]{
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO
            });
            BigDecimal[] row = byDate.get(d);
            switch (t.getTransactionType()) {
                case "SALE"                      -> row[0] = row[0].add(t.getAmount());
                case "REFUND","VOID","RETURN"     -> row[1] = row[1].add(t.getAmount());
                case "ADJUSTMENT"                -> row[2] = row[2].add(t.getAmount());
            }
        }

        List<Map<String, Object>> dailyBreakdown = byDate.entrySet().stream()
            .map(entry -> {
                BigDecimal[] row  = entry.getValue();
                BigDecimal dayNet = row[0].add(row[1]).add(row[2]);
                Map<String, Object> dr = new LinkedHashMap<>();
                dr.put("date",        entry.getKey().toString());
                dr.put("grossSales",  row[0]);
                dr.put("refunds",     row[1]);
                dr.put("adjustments", row[2]);
                dr.put("netSales",    dayNet);
                return dr;
            })
            .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("month",            ym.toString());
        result.put("grossSales",       grossSales);
        result.put("refundsTotal",     refunds);
        result.put("adjustmentsTotal", adjustments);
        result.put("netSales",         netSales);
        result.put("dailyBreakdown",   dailyBreakdown);

        return ResponseEntity.ok(result);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // GET /api/reports/source-breakdown?month=YYYY-MM
    // Returns: [ { source, orderCount, revenue, pct } ]
    // ──────────────────────────────────────────────────────────────────────────
    @GetMapping("/source-breakdown")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getSourceBreakdown(@RequestParam(defaultValue = "") String month) {
        YearMonth ym;
        try { ym = parseMonth(month); } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid month format. Use YYYY-MM."));
        }
        LocalDate start = ym.atDay(1);
        LocalDate end   = ym.atEndOfMonth();

        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(
                "SELECT source, COUNT(*) as order_count, COALESCE(SUM(total), 0) as revenue " +
                "FROM orders " +
                "WHERE status != 'CANCELLED' " +
                "  AND created_at::date >= :startDate " +
                "  AND created_at::date <= :endDate " +
                "GROUP BY source " +
                "ORDER BY revenue DESC")
                .setParameter("startDate", start)
                .setParameter("endDate",   end)
                .getResultList();

        BigDecimal grandRevenue = rows.stream()
                .map(r -> r[2] != null ? (BigDecimal) r[2] : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<Map<String, Object>> data = rows.stream().map(r -> {
            BigDecimal rev = r[2] != null ? (BigDecimal) r[2] : BigDecimal.ZERO;
            BigDecimal pct = grandRevenue.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
                    : rev.multiply(BigDecimal.valueOf(100)).divide(grandRevenue, 1, RoundingMode.HALF_UP);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("source",     r[0]);
            m.put("orderCount", ((Number) r[1]).longValue());
            m.put("revenue",    rev);
            m.put("pct",        pct);
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("month",          ym.toString());
        result.put("totalRevenue",   grandRevenue);
        result.put("breakdown",      data);
        return ResponseEntity.ok(result);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // GET /api/reports/top-agents?month=YYYY-MM
    // Returns: top 10 agents/resellers/distributors by revenue
    // ──────────────────────────────────────────────────────────────────────────
    @GetMapping("/top-agents")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getTopAgents(@RequestParam(defaultValue = "") String month) {
        YearMonth ym;
        try { ym = parseMonth(month); } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid month format. Use YYYY-MM."));
        }
        LocalDate start = ym.atDay(1);
        LocalDate end   = ym.atEndOfMonth();

        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(
                "SELECT agent_name, source, COUNT(*) as order_count, COALESCE(SUM(total), 0) as revenue " +
                "FROM orders " +
                "WHERE status != 'CANCELLED' " +
                "  AND source IN ('AGENT', 'RESELLER', 'DISTRIBUTOR') " +
                "  AND agent_name IS NOT NULL AND agent_name != '' " +
                "  AND created_at::date >= :startDate " +
                "  AND created_at::date <= :endDate " +
                "GROUP BY agent_name, source " +
                "ORDER BY revenue DESC " +
                "LIMIT 10")
                .setParameter("startDate", start)
                .setParameter("endDate",   end)
                .getResultList();

        List<Map<String, Object>> data = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Object[] r = rows.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rank",       i + 1);
            m.put("agentName",  r[0]);
            m.put("source",     r[1]);
            m.put("orderCount", ((Number) r[2]).longValue());
            m.put("revenue",    r[3] != null ? (BigDecimal) r[3] : BigDecimal.ZERO);
            data.add(m);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("month",  ym.toString());
        result.put("agents", data);
        return ResponseEntity.ok(result);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // GET /api/reports/top-dates?month=YYYY-MM
    // Returns: top 3 highest-revenue dates in the month
    // ──────────────────────────────────────────────────────────────────────────
    @GetMapping("/top-dates")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getTopDates(@RequestParam(defaultValue = "") String month) {
        YearMonth ym;
        try { ym = parseMonth(month); } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid month format. Use YYYY-MM."));
        }
        LocalDate start = ym.atDay(1);
        LocalDate end   = ym.atEndOfMonth();

        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(
                "SELECT created_at::date as sale_date, COUNT(*) as order_count, COALESCE(SUM(total), 0) as revenue " +
                "FROM orders " +
                "WHERE status != 'CANCELLED' " +
                "  AND created_at::date >= :startDate " +
                "  AND created_at::date <= :endDate " +
                "GROUP BY created_at::date " +
                "ORDER BY revenue DESC " +
                "LIMIT 3")
                .setParameter("startDate", start)
                .setParameter("endDate",   end)
                .getResultList();

        List<Map<String, Object>> data = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Object[] r = rows.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rank",       i + 1);
            m.put("date",       r[0] != null ? r[0].toString() : "");
            m.put("orderCount", ((Number) r[1]).longValue());
            m.put("revenue",    r[2] != null ? (BigDecimal) r[2] : BigDecimal.ZERO);
            data.add(m);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("month", ym.toString());
        result.put("dates", data);
        return ResponseEntity.ok(result);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // GET /api/reports/pizza-summary?month=YYYY-MM
    // Returns: totalQty (all Pizza Box items), top 5 pizza box SKUs by qty
    // ──────────────────────────────────────────────────────────────────────────
    @GetMapping("/pizza-summary")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getPizzaSummary(@RequestParam(defaultValue = "") String month) {
        YearMonth ym;
        try { ym = parseMonth(month); } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid month format. Use YYYY-MM."));
        }
        LocalDate start = ym.atDay(1);
        LocalDate end   = ym.atEndOfMonth();

        // All pizza box items (case-insensitive match on product name)
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(
                "SELECT oi.product_name, SUM(oi.quantity) as qty, COALESCE(SUM(oi.subtotal), 0) as revenue " +
                "FROM order_items oi " +
                "JOIN orders o ON oi.order_id = o.id " +
                "WHERE o.status != 'CANCELLED' " +
                "  AND o.created_at::date >= :startDate " +
                "  AND o.created_at::date <= :endDate " +
                "  AND oi.product_name ILIKE '%Pizza Box%' " +
                "GROUP BY oi.product_name " +
                "ORDER BY qty DESC")
                .setParameter("startDate", start)
                .setParameter("endDate",   end)
                .getResultList();

        long totalQty = rows.stream()
                .mapToLong(r -> r[1] != null ? ((Number) r[1]).longValue() : 0)
                .sum();

        List<Map<String, Object>> top5 = rows.stream().limit(5).map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("productName", r[0]);
            m.put("qty",         r[1] != null ? ((Number) r[1]).longValue() : 0L);
            m.put("revenue",     r[2] != null ? (BigDecimal) r[2] : BigDecimal.ZERO);
            return m;
        }).collect(Collectors.toList());

        // Channel split: direct vs ecommerce (single conditional-aggregation query)
        Object[] split = (Object[]) entityManager.createNativeQuery(
                "SELECT " +
                "  COALESCE(SUM(CASE WHEN o.source != 'ECOMMERCE' THEN oi.quantity ELSE 0 END), 0) as direct_qty, " +
                "  COALESCE(SUM(CASE WHEN o.source  = 'ECOMMERCE' THEN oi.quantity ELSE 0 END), 0) as ecom_qty, " +
                "  COALESCE(SUM(CASE WHEN o.source != 'ECOMMERCE' THEN oi.subtotal ELSE 0 END), 0) as direct_rev, " +
                "  COALESCE(SUM(CASE WHEN o.source  = 'ECOMMERCE' THEN oi.subtotal ELSE 0 END), 0) as ecom_rev, " +
                "  COALESCE(SUM(oi.subtotal), 0) as total_rev " +
                "FROM order_items oi " +
                "JOIN orders o ON oi.order_id = o.id " +
                "WHERE o.status != 'CANCELLED' " +
                "  AND o.created_at::date >= :startDate " +
                "  AND o.created_at::date <= :endDate " +
                "  AND oi.product_name ILIKE '%Pizza Box%'")
                .setParameter("startDate", start)
                .setParameter("endDate",   end)
                .getSingleResult();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("month",        ym.toString());
        result.put("totalQty",     totalQty);
        result.put("directQty",    split[0] != null ? ((Number) split[0]).longValue() : 0L);
        result.put("ecomQty",      split[1] != null ? ((Number) split[1]).longValue() : 0L);
        result.put("directRevenue", split[2] != null ? (BigDecimal) split[2] : BigDecimal.ZERO);
        result.put("ecomRevenue",   split[3] != null ? (BigDecimal) split[3] : BigDecimal.ZERO);
        result.put("totalRevenue",  split[4] != null ? (BigDecimal) split[4] : BigDecimal.ZERO);
        result.put("top5",         top5);
        return ResponseEntity.ok(result);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // GET /api/reports/non-pizza-summary?month=YYYY-MM
    // Returns totals for all non-pizza-box items, split by channel, + top products
    // ──────────────────────────────────────────────────────────────────────────
    @GetMapping("/non-pizza-summary")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getNonPizzaSummary(@RequestParam(defaultValue = "") String month) {
        YearMonth ym;
        try { ym = parseMonth(month); } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid month format. Use YYYY-MM."));
        }
        LocalDate start = ym.atDay(1);
        LocalDate end   = ym.atEndOfMonth();

        // Channel-split aggregation for non-pizza items
        Object[] split = (Object[]) entityManager.createNativeQuery(
                "SELECT " +
                "  COALESCE(SUM(CASE WHEN o.source != 'ECOMMERCE' THEN oi.quantity ELSE 0 END), 0) as direct_qty, " +
                "  COALESCE(SUM(CASE WHEN o.source  = 'ECOMMERCE' THEN oi.quantity ELSE 0 END), 0) as ecom_qty, " +
                "  COALESCE(SUM(CASE WHEN o.source != 'ECOMMERCE' THEN oi.subtotal ELSE 0 END), 0) as direct_rev, " +
                "  COALESCE(SUM(CASE WHEN o.source  = 'ECOMMERCE' THEN oi.subtotal ELSE 0 END), 0) as ecom_rev, " +
                "  COALESCE(SUM(oi.quantity), 0) as total_qty, " +
                "  COALESCE(SUM(oi.subtotal), 0) as total_rev " +
                "FROM order_items oi " +
                "JOIN orders o ON oi.order_id = o.id " +
                "WHERE o.status != 'CANCELLED' " +
                "  AND o.created_at::date >= :startDate " +
                "  AND o.created_at::date <= :endDate " +
                "  AND oi.product_name NOT ILIKE '%Pizza Box%'")
                .setParameter("startDate", start)
                .setParameter("endDate",   end)
                .getSingleResult();

        // Top 10 non-pizza products with channel split
        @SuppressWarnings("unchecked")
        List<Object[]> topRows = entityManager.createNativeQuery(
                "SELECT oi.product_name, " +
                "  COALESCE(SUM(CASE WHEN o.source != 'ECOMMERCE' THEN oi.quantity ELSE 0 END), 0) as direct_qty, " +
                "  COALESCE(SUM(CASE WHEN o.source  = 'ECOMMERCE' THEN oi.quantity ELSE 0 END), 0) as ecom_qty, " +
                "  COALESCE(SUM(oi.quantity), 0) as total_qty, " +
                "  COALESCE(SUM(oi.subtotal), 0) as revenue " +
                "FROM order_items oi " +
                "JOIN orders o ON oi.order_id = o.id " +
                "WHERE o.status != 'CANCELLED' " +
                "  AND o.created_at::date >= :startDate " +
                "  AND o.created_at::date <= :endDate " +
                "  AND oi.product_name NOT ILIKE '%Pizza Box%' " +
                "GROUP BY oi.product_name " +
                "ORDER BY total_qty DESC " +
                "LIMIT 10")
                .setParameter("startDate", start)
                .setParameter("endDate",   end)
                .getResultList();

        List<Map<String, Object>> topProducts = topRows.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("productName", r[0]);
            m.put("directQty",   r[1] != null ? ((Number) r[1]).longValue() : 0L);
            m.put("ecomQty",     r[2] != null ? ((Number) r[2]).longValue() : 0L);
            m.put("totalQty",    r[3] != null ? ((Number) r[3]).longValue() : 0L);
            m.put("revenue",     r[4] != null ? (BigDecimal) r[4] : BigDecimal.ZERO);
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("month",        ym.toString());
        result.put("directQty",    split[0] != null ? ((Number) split[0]).longValue() : 0L);
        result.put("ecomQty",      split[1] != null ? ((Number) split[1]).longValue() : 0L);
        result.put("directRevenue", split[2] != null ? (BigDecimal) split[2] : BigDecimal.ZERO);
        result.put("ecomRevenue",   split[3] != null ? (BigDecimal) split[3] : BigDecimal.ZERO);
        result.put("totalQty",     split[4] != null ? ((Number) split[4]).longValue() : 0L);
        result.put("totalRevenue", split[5] != null ? (BigDecimal) split[5] : BigDecimal.ZERO);
        result.put("topProducts",  topProducts);
        return ResponseEntity.ok(result);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // GET /api/reports/daily-order-summary?month=YYYY-MM
    // Per-day breakdown: direct orders, ecom orders, total orders, pizza box qty, revenue
    // ──────────────────────────────────────────────────────────────────────────
    @GetMapping("/daily-order-summary")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getDailyOrderSummary(@RequestParam(defaultValue = "") String month) {
        YearMonth ym;
        try { ym = parseMonth(month); } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid month format. Use YYYY-MM."));
        }
        LocalDate start = ym.atDay(1);
        LocalDate end   = ym.atEndOfMonth();

        // Per-day aggregation; LEFT JOIN pizza subquery keeps order rows 1:1
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(
                "SELECT " +
                "  o.created_at::date as sale_date, " +
                "  COUNT(CASE WHEN o.source != 'ECOMMERCE' THEN 1 END) as direct_orders, " +
                "  COUNT(CASE WHEN o.source  = 'ECOMMERCE' THEN 1 END) as ecom_orders, " +
                "  COUNT(*) as total_orders, " +
                "  COALESCE(SUM(pizza.pizza_qty), 0) as pizza_qty, " +
                "  COALESCE(SUM(o.total), 0) as revenue " +
                "FROM orders o " +
                "LEFT JOIN ( " +
                "  SELECT order_id, SUM(quantity) as pizza_qty " +
                "  FROM order_items " +
                "  WHERE product_name ILIKE '%Pizza Box%' " +
                "  GROUP BY order_id " +
                ") pizza ON pizza.order_id = o.id " +
                "WHERE o.status != 'CANCELLED' " +
                "  AND o.created_at::date >= :startDate " +
                "  AND o.created_at::date <= :endDate " +
                "GROUP BY o.created_at::date " +
                "ORDER BY o.created_at::date ASC")
                .setParameter("startDate", start)
                .setParameter("endDate",   end)
                .getResultList();

        List<Map<String, Object>> data = rows.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date",          r[0] != null ? r[0].toString() : "");
            m.put("directOrders",  r[1] != null ? ((Number) r[1]).longValue() : 0L);
            m.put("ecomOrders",    r[2] != null ? ((Number) r[2]).longValue() : 0L);
            m.put("totalOrders",   r[3] != null ? ((Number) r[3]).longValue() : 0L);
            m.put("pizzaBoxQty",   r[4] != null ? ((Number) r[4]).longValue() : 0L);
            m.put("revenue",       r[5] != null ? (BigDecimal) r[5] : BigDecimal.ZERO);
            return m;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(Map.of("month", ym.toString(), "days", data));
    }

    // ──────────────────────────────────────────────────────────────────────────
    // GET /api/reports/hot-selling?month=YYYY-MM
    // Returns: top 10 items sold that belong to HOT or SELLING tagged products
    // ──────────────────────────────────────────────────────────────────────────
    @GetMapping("/hot-selling")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getHotSelling(@RequestParam(defaultValue = "") String month) {
        YearMonth ym;
        try { ym = parseMonth(month); } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid month format. Use YYYY-MM."));
        }
        LocalDate start = ym.atDay(1);
        LocalDate end   = ym.atEndOfMonth();

        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(
                "SELECT oi.product_name, p.selling_tag, SUM(oi.quantity) as qty, COALESCE(SUM(oi.subtotal), 0) as revenue " +
                "FROM order_items oi " +
                "JOIN orders o ON oi.order_id = o.id " +
                "JOIN products p ON oi.product_id = p.id " +
                "WHERE o.status != 'CANCELLED' " +
                "  AND o.created_at::date >= :startDate " +
                "  AND o.created_at::date <= :endDate " +
                "  AND p.selling_tag IN ('HOT', 'SELLING') " +
                "GROUP BY oi.product_name, p.selling_tag " +
                "ORDER BY qty DESC " +
                "LIMIT 10")
                .setParameter("startDate", start)
                .setParameter("endDate",   end)
                .getResultList();

        List<Map<String, Object>> data = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Object[] r = rows.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rank",        i + 1);
            m.put("productName", r[0]);
            m.put("sellingTag",  r[1]);
            m.put("qty",         r[2] != null ? ((Number) r[2]).longValue() : 0L);
            m.put("revenue",     r[3] != null ? (BigDecimal) r[3] : BigDecimal.ZERO);
            data.add(m);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("month", ym.toString());
        result.put("items", data);
        return ResponseEntity.ok(result);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // GET /api/reports/delivery-fees?month=YYYY-MM
    // Returns: orders that had delivery_fee > 0, plus totalFees sum
    // ──────────────────────────────────────────────────────────────────────────
    @GetMapping("/delivery-fees")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getDeliveryFees(@RequestParam(defaultValue = "") String month) {
        YearMonth ym;
        try { ym = parseMonth(month); } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid month format. Use YYYY-MM."));
        }
        LocalDate start = ym.atDay(1);
        LocalDate end   = ym.atEndOfMonth();

        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(
                "SELECT o.id, o.customer_name, o.source, o.delivery_fee, o.total, " +
                "       o.created_at::date as sale_date " +
                "FROM orders o " +
                "WHERE o.status != 'CANCELLED' " +
                "  AND o.delivery_fee > 0 " +
                "  AND o.created_at::date >= :startDate " +
                "  AND o.created_at::date <= :endDate " +
                "ORDER BY o.created_at DESC")
                .setParameter("startDate", start)
                .setParameter("endDate",   end)
                .getResultList();

        BigDecimal totalFees = rows.stream()
                .map(r -> r[3] != null ? (BigDecimal) r[3] : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<Map<String, Object>> orders = rows.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("orderId",      r[0]);
            m.put("customerName", r[1]);
            m.put("source",       r[2]);
            m.put("deliveryFee",  r[3] != null ? (BigDecimal) r[3] : BigDecimal.ZERO);
            m.put("orderTotal",   r[4] != null ? (BigDecimal) r[4] : BigDecimal.ZERO);
            m.put("date",         r[5] != null ? r[5].toString() : "");
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("month",      ym.toString());
        result.put("totalFees",  totalFees);
        result.put("orderCount", orders.size());
        result.put("orders",     orders);
        return ResponseEntity.ok(result);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // GET /api/reports/expense-breakdown?month=YYYY-MM
    // Returns: expense line items grouped by description + grand total
    // ──────────────────────────────────────────────────────────────────────────
    @GetMapping("/expense-breakdown")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getExpenseBreakdown(@RequestParam(defaultValue = "") String month) {
        YearMonth ym;
        try { ym = parseMonth(month); } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid month format. Use YYYY-MM."));
        }
        LocalDate start = ym.atDay(1);
        LocalDate end   = ym.atEndOfMonth();

        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery(
                "SELECT ei.item_description, SUM(ei.amount) as total_amount, COUNT(*) as cnt " +
                "FROM expense_items ei " +
                "JOIN expenses e ON ei.expense_id = e.id " +
                "WHERE e.date >= :startDate AND e.date <= :endDate " +
                "GROUP BY ei.item_description " +
                "ORDER BY total_amount DESC")
                .setParameter("startDate", start)
                .setParameter("endDate",   end)
                .getResultList();

        BigDecimal grandTotal = rows.stream()
                .map(r -> r[1] != null ? (BigDecimal) r[1] : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<Map<String, Object>> breakdown = rows.stream().map(r -> {
            BigDecimal amt = r[1] != null ? (BigDecimal) r[1] : BigDecimal.ZERO;
            BigDecimal pct = grandTotal.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
                    : amt.multiply(BigDecimal.valueOf(100)).divide(grandTotal, 1, RoundingMode.HALF_UP);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("description", r[0]);
            m.put("totalAmount", amt);
            m.put("count",       ((Number) r[2]).longValue());
            m.put("pct",         pct);
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("month",      ym.toString());
        result.put("grandTotal", grandTotal);
        result.put("breakdown",  breakdown);
        return ResponseEntity.ok(result);
    }

    // ── E-commerce Breakdown ──────────────────────────────────────────────
    @GetMapping("/ecommerce-breakdown")
    public ResponseEntity<?> getEcommerceBreakdown(@RequestParam(required = false) String month) {
        try {
            java.time.YearMonth ym = (month != null && !month.isBlank())
                ? java.time.YearMonth.parse(month)
                : java.time.YearMonth.now();
            java.time.LocalDate start = ym.atDay(1);
            java.time.LocalDate end   = ym.atEndOfMonth();

            // Overall ecommerce totals
            String totalSql = "SELECT COUNT(*), COALESCE(SUM(o.total),0), " +
                              "  COALESCE(SUM(oi_sum.qty),0) " +
                              "FROM orders o " +
                              "LEFT JOIN (" +
                              "  SELECT order_id, SUM(quantity) as qty FROM order_items GROUP BY order_id" +
                              ") oi_sum ON oi_sum.order_id = o.id " +
                              "WHERE DATE(o.created_at) BETWEEN :start AND :end " +
                              "  AND o.status != 'CANCELLED' " +
                              "  AND o.source = 'ECOMMERCE'";

            Object[] totRow = (Object[]) entityManager.createNativeQuery(totalSql)
                .setParameter("start", start).setParameter("end", end).getSingleResult();
            long   totalOrders  = ((Number) totRow[0]).longValue();
            double totalRevenue = ((Number) totRow[1]).doubleValue();
            long   totalItems   = ((Number) totRow[2]).longValue();

            // Per-platform breakdown
            String platSql = "SELECT COALESCE(o.ecommerce_platform, 'UNKNOWN'), " +
                             "  COUNT(*), COALESCE(SUM(o.total),0) " +
                             "FROM orders o " +
                             "WHERE DATE(o.created_at) BETWEEN :start AND :end " +
                             "  AND o.status != 'CANCELLED' " +
                             "  AND o.source = 'ECOMMERCE' " +
                             "GROUP BY o.ecommerce_platform ORDER BY SUM(o.total) DESC";

            @SuppressWarnings("unchecked")
            java.util.List<Object[]> platRows = entityManager.createNativeQuery(platSql)
                .setParameter("start", start).setParameter("end", end).getResultList();

            java.util.List<java.util.Map<String, Object>> platforms = new java.util.ArrayList<>();
            for (Object[] r : platRows) {
                String platform = String.valueOf(r[0]);
                long   cnt      = ((Number) r[1]).longValue();
                double rev      = ((Number) r[2]).doubleValue();

                // Top 3 products for this platform
                String topSql = "SELECT p.name, SUM(oi.quantity) AS qty, SUM(oi.subtotal) AS rev " +
                                "FROM order_items oi " +
                                "JOIN orders o ON o.id = oi.order_id " +
                                "JOIN products p ON p.id = oi.product_id " +
                                "WHERE DATE(o.created_at) BETWEEN :start AND :end " +
                                "  AND o.status != 'CANCELLED' " +
                                "  AND o.source = 'ECOMMERCE' " +
                                "  AND COALESCE(o.ecommerce_platform,'UNKNOWN') = :plat " +
                                "GROUP BY p.id, p.name ORDER BY qty DESC LIMIT 3";

                @SuppressWarnings("unchecked")
                java.util.List<Object[]> topRows = entityManager.createNativeQuery(topSql)
                    .setParameter("start", start)
                    .setParameter("end",   end)
                    .setParameter("plat",  platform)
                    .getResultList();

                java.util.List<java.util.Map<String, Object>> top3 = new java.util.ArrayList<>();
                for (Object[] tr : topRows) {
                    java.util.Map<String, Object> tp = new java.util.LinkedHashMap<>();
                    tp.put("productName", tr[0]);
                    tp.put("qtySold",     ((Number) tr[1]).longValue());
                    tp.put("revenue",     ((Number) tr[2]).doubleValue());
                    top3.add(tp);
                }

                java.util.Map<String, Object> pm = new java.util.LinkedHashMap<>();
                pm.put("platform",    platform);
                pm.put("orderCount",  cnt);
                pm.put("revenue",     rev);
                pm.put("avgOrder",    cnt > 0 ? Math.round(rev / cnt * 100.0) / 100.0 : 0);
                pm.put("percentage",  totalRevenue > 0
                    ? Math.round(rev / totalRevenue * 1000.0) / 10.0 : 0.0);
                pm.put("topProducts", top3);
                platforms.add(pm);
            }

            return ResponseEntity.ok(java.util.Map.of(
                "totalOrders",  totalOrders,
                "totalRevenue", totalRevenue,
                "totalItems",   totalItems,
                "avgOrder",     totalOrders > 0 ? Math.round(totalRevenue / totalOrders * 100.0) / 100.0 : 0,
                "platforms",    platforms
            ));

        } catch (Exception e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("message", e.getMessage()));
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // GET /api/reports/daily-reports-list
    // Returns all closed daily_reports ordered by date desc with resolved closedByName
    // ──────────────────────────────────────────────────────────────────────────
    @GetMapping("/daily-reports-list")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getDailyReportsList() {
        // Fetch all reports ordered by date desc
        List<DailyReport> reports = dailyReportRepository
                .findByReportDateBetweenOrderByReportDateDesc(
                        LocalDate.of(2020, 1, 1), LocalDate.now());

        // Build userId → fullName lookup map
        Map<Long, String> userNames = userRepository.findAll().stream()
                .collect(Collectors.toMap(User::getId, User::getFullName, (a, b) -> a));

        List<Map<String, Object>> list = reports.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("reportDate",   r.getReportDate() != null ? r.getReportDate().toString() : null);
            m.put("closedAt",     r.getClosedAt()   != null ? r.getClosedAt().toString()   : null);
            m.put("closedBy",     r.getClosedBy());
            m.put("closedByName", r.getClosedBy() != null
                    ? userNames.getOrDefault(r.getClosedBy(), "Unknown") : "—");
            m.put("totalOrders",  r.getTotalOrders());
            // Use ledger grossSales if available, else fall back to legacy totalRevenue
            m.put("grossSales",   r.getGrossSales()  != null ? r.getGrossSales()  : r.getTotalRevenue());
            m.put("netSales",     r.getNetSales()    != null ? r.getNetSales()    : r.getTotalRevenue());
            m.put("totalRevenue", r.getTotalRevenue());
            return m;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(Map.of("reports", list, "total", list.size()));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // GET /api/reports/monthly-corporate?month=YYYY-MM
    // Consolidated, reconciling payload for the corporate monthly PDF.
    //   { month, prevMonth, summary, reconciliation, channels, pizza, expenses, mom }
    //
    // Revenue basis: NET is the transaction ledger (SALE positive; REFUND/VOID/RETURN
    // negative; ADJUSTMENT ±) — identical to accounting-summary, so the headline ties to
    // the channel breakdown by construction. Product sections (pizza) are GROSS because the
    // ledger has no line-item detail; the reconciliation block bridges gross→net.
    // ══════════════════════════════════════════════════════════════════════════
    @GetMapping("/monthly-corporate")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getMonthlyCorporate(@RequestParam(defaultValue = "") String month) {
        YearMonth ym;
        try { ym = parseMonth(month); } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid month format. Use YYYY-MM."));
        }
        YearMonth prevYm = ym.minusMonths(1);

        Map<String, Object> cur  = computeMonthBlocks(ym);
        Map<String, Object> prev = computeMonthBlocks(prevYm);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("month",          ym.toString());
        result.put("prevMonth",      prevYm.toString());
        result.put("summary",        cur.get("summary"));
        result.put("reconciliation", cur.get("reconciliation"));
        result.put("channels",       cur.get("channels"));
        result.put("pizza",          cur.get("pizza"));
        result.put("expenses",       cur.get("expenses"));
        result.put("mom",            buildMom(cur, prev, prevYm));
        return ResponseEntity.ok(result);
    }

    /** Pizza-box predicate, mirroring DashboardController.classifyProduct: category match,
     *  or (blank category AND name contains "pizza box"). LEFT-JOIN safe (p may be null). */
    private static final String PIZZA_PRED =
            "(p.category ILIKE 'Pizza Box' OR " +
            " (COALESCE(NULLIF(TRIM(p.category), ''), '') = '' AND oi.product_name ILIKE '%pizza box%'))";

    /** Compute every report block for one month (reused for current + previous). */
    private Map<String, Object> computeMonthBlocks(YearMonth ym) {
        LocalDate start = ym.atDay(1);
        LocalDate end   = ym.atEndOfMonth();

        // ── Ledger: typed net (matches accounting-summary) ──────────────────────
        List<Transaction> txns = transactionService.getByDateRange(start, end);
        BigDecimal grossSales = BigDecimal.ZERO, refunds = BigDecimal.ZERO, adjustments = BigDecimal.ZERO;
        for (Transaction t : txns) {
            switch (t.getTransactionType()) {
                case "SALE"                   -> grossSales  = grossSales.add(t.getAmount());
                case "REFUND","VOID","RETURN" -> refunds     = refunds.add(t.getAmount());
                case "ADJUSTMENT"             -> adjustments = adjustments.add(t.getAmount());
            }
        }
        BigDecimal netSales = grossSales.add(refunds).add(adjustments);

        // ── Orders (billed = not CANCELLED, not PENDING_COLLECTION) ─────────────
        long totalOrders = ((Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM orders " +
                "WHERE status NOT IN ('CANCELLED','PENDING_COLLECTION','SCHEDULED_DELIVERY') " +
                "  AND created_at::date BETWEEN :start AND :end")
                .setParameter("start", start).setParameter("end", end)
                .getSingleResult()).longValue();

        long totalItemsSold = ((Number) entityManager.createNativeQuery(
                "SELECT COALESCE(SUM(oi.quantity),0) FROM order_items oi " +
                "JOIN orders o ON oi.order_id = o.id " +
                "WHERE o.status NOT IN ('CANCELLED','PENDING_COLLECTION','SCHEDULED_DELIVERY') " +
                "  AND o.created_at::date BETWEEN :start AND :end")
                .setParameter("start", start).setParameter("end", end)
                .getSingleResult()).longValue();

        // ── Expenses (voided excluded) ──────────────────────────────────────────
        BigDecimal totalExpenses = nz(expenseRepository.sumNonVoidedForDateRange(start, end));
        BigDecimal netProfit     = netSales.subtract(totalExpenses);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalOrders",    totalOrders);
        summary.put("netRevenue",     netSales);
        summary.put("totalExpenses",  totalExpenses);
        summary.put("netProfit",      netProfit);
        summary.put("totalItemsSold", totalItemsSold);

        Map<String, Object> reconciliation = new LinkedHashMap<>();
        reconciliation.put("grossSales",       grossSales);
        reconciliation.put("refundsTotal",     refunds);       // negative (REFUND/VOID/RETURN)
        reconciliation.put("adjustmentsTotal", adjustments);
        reconciliation.put("netSales",         netSales);

        Map<String, Object> blocks = new LinkedHashMap<>();
        blocks.put("summary",        summary);
        blocks.put("reconciliation", reconciliation);
        blocks.put("channels",       computeChannelsNet(start, end, netSales));
        blocks.put("pizza",          computePizzaGross(start, end));
        blocks.put("expenses",       computeExpenses(ym, start, end, netSales, totalExpenses));
        return blocks;
    }

    /** Net revenue + order counts per channel (Direct vs Ecommerce → TikTok/Lazada/Shopee).
     *  Net is the ledger joined to orders; the residual (manual adjustments with no order)
     *  is surfaced as "unattributed" so the parts always sum to netSales. */
    private Map<String, Object> computeChannelsNet(LocalDate start, LocalDate end, BigDecimal netSales) {
        @SuppressWarnings("unchecked")
        List<Object[]> netRows = entityManager.createNativeQuery(
                "SELECT CASE WHEN o.source='ECOMMERCE' THEN COALESCE(o.ecommerce_platform,'UNKNOWN') ELSE 'DIRECT' END AS ch, " +
                "       COALESCE(SUM(t.amount),0) AS net " +
                "FROM transactions t JOIN orders o ON t.order_id = o.id " +
                "WHERE t.effective_date BETWEEN :start AND :end " +
                "  AND t.transaction_type IN ('SALE','REFUND','VOID','RETURN','ADJUSTMENT','DISCOUNT') " +
                "GROUP BY ch")
                .setParameter("start", start).setParameter("end", end)
                .getResultList();

        BigDecimal directNet = BigDecimal.ZERO, ecomNet = BigDecimal.ZERO, platOtherNet = BigDecimal.ZERO;
        Map<String, BigDecimal> platNet = new LinkedHashMap<>();
        platNet.put("TIKTOK", BigDecimal.ZERO);
        platNet.put("LAZADA", BigDecimal.ZERO);
        platNet.put("SHOPEE", BigDecimal.ZERO);
        for (Object[] r : netRows) {
            String ch = String.valueOf(r[0]);
            BigDecimal net = bd(r[1]);
            if ("DIRECT".equals(ch)) { directNet = directNet.add(net); }
            else {
                ecomNet = ecomNet.add(net);
                if (platNet.containsKey(ch)) platNet.put(ch, platNet.get(ch).add(net));
                else platOtherNet = platOtherNet.add(net);
            }
        }
        BigDecimal unattributed = netSales.subtract(directNet.add(ecomNet));

        @SuppressWarnings("unchecked")
        List<Object[]> cntRows = entityManager.createNativeQuery(
                "SELECT CASE WHEN source='ECOMMERCE' THEN COALESCE(ecommerce_platform,'UNKNOWN') ELSE 'DIRECT' END AS ch, COUNT(*) " +
                "FROM orders WHERE status NOT IN ('CANCELLED','PENDING_COLLECTION','SCHEDULED_DELIVERY') " +
                "  AND created_at::date BETWEEN :start AND :end GROUP BY ch")
                .setParameter("start", start).setParameter("end", end)
                .getResultList();

        long directCount = 0, ecomCount = 0, platOtherCount = 0;
        Map<String, Long> platCount = new LinkedHashMap<>();
        platCount.put("TIKTOK", 0L); platCount.put("LAZADA", 0L); platCount.put("SHOPEE", 0L);
        for (Object[] r : cntRows) {
            String ch = String.valueOf(r[0]);
            long c = ((Number) r[1]).longValue();
            if ("DIRECT".equals(ch)) { directCount += c; }
            else {
                ecomCount += c;
                if (platCount.containsKey(ch)) platCount.put(ch, platCount.get(ch) + c);
                else platOtherCount += c;
            }
        }

        List<Map<String, Object>> platforms = new ArrayList<>();
        for (String p : new String[]{"TIKTOK", "LAZADA", "SHOPEE"}) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("platform",   p);
            m.put("orderCount", platCount.get(p));
            m.put("netRevenue", platNet.get(p));
            platforms.add(m);
        }
        if (platOtherCount > 0 || platOtherNet.signum() != 0) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("platform", "OTHER");
            m.put("orderCount", platOtherCount);
            m.put("netRevenue", platOtherNet);
            platforms.add(m);
        }

        Map<String, Object> direct = new LinkedHashMap<>();
        direct.put("orderCount", directCount);
        direct.put("netRevenue", directNet);

        Map<String, Object> ecom = new LinkedHashMap<>();
        ecom.put("orderCount", ecomCount);
        ecom.put("netRevenue", ecomNet);
        ecom.put("platforms",  platforms);

        Map<String, Object> channels = new LinkedHashMap<>();
        channels.put("direct",       direct);
        channels.put("ecommerce",    ecom);
        channels.put("unattributed", unattributed);
        channels.put("netRevenue",   netSales);
        return channels;
    }

    /** Pizza-box gross sales by category (qty + subtotal), split Direct/Ecommerce + per platform,
     *  plus pizza-vs-rest share of gross item sales. Gross because the ledger has no item detail. */
    private Map<String, Object> computePizzaGross(LocalDate start, LocalDate end) {
        String billed = "o.status NOT IN ('CANCELLED','PENDING_COLLECTION','SCHEDULED_DELIVERY') AND o.created_at::date BETWEEN :start AND :end";

        Object[] tot = (Object[]) entityManager.createNativeQuery(
                "SELECT " +
                " COALESCE(SUM(CASE WHEN o.source!='ECOMMERCE' THEN oi.quantity ELSE 0 END),0), " +
                " COALESCE(SUM(CASE WHEN o.source ='ECOMMERCE' THEN oi.quantity ELSE 0 END),0), " +
                " COALESCE(SUM(CASE WHEN o.source!='ECOMMERCE' THEN oi.subtotal ELSE 0 END),0), " +
                " COALESCE(SUM(CASE WHEN o.source ='ECOMMERCE' THEN oi.subtotal ELSE 0 END),0), " +
                " COALESCE(SUM(oi.quantity),0), COALESCE(SUM(oi.subtotal),0) " +
                "FROM order_items oi JOIN orders o ON oi.order_id = o.id " +
                "LEFT JOIN products p ON p.id = oi.product_id " +
                "WHERE " + billed + " AND " + PIZZA_PRED)
                .setParameter("start", start).setParameter("end", end)
                .getSingleResult();

        long       directQty   = ((Number) tot[0]).longValue();
        long       ecomQty     = ((Number) tot[1]).longValue();
        BigDecimal directGross = bd(tot[2]);
        BigDecimal ecomGross   = bd(tot[3]);
        long       totalQty    = ((Number) tot[4]).longValue();
        BigDecimal totalGross  = bd(tot[5]);

        @SuppressWarnings("unchecked")
        List<Object[]> platRows = entityManager.createNativeQuery(
                "SELECT COALESCE(o.ecommerce_platform,'UNKNOWN'), COALESCE(SUM(oi.quantity),0), COALESCE(SUM(oi.subtotal),0) " +
                "FROM order_items oi JOIN orders o ON oi.order_id = o.id " +
                "LEFT JOIN products p ON p.id = oi.product_id " +
                "WHERE " + billed + " AND o.source='ECOMMERCE' AND " + PIZZA_PRED + " " +
                "GROUP BY o.ecommerce_platform")
                .setParameter("start", start).setParameter("end", end)
                .getResultList();
        Map<String, long[]> platQty = new LinkedHashMap<>();
        Map<String, BigDecimal> platGross = new LinkedHashMap<>();
        for (Object[] r : platRows) {
            String plat = String.valueOf(r[0]);
            platQty.put(plat, new long[]{((Number) r[1]).longValue()});
            platGross.put(plat, bd(r[2]));
        }
        List<Map<String, Object>> platforms = new ArrayList<>();
        for (String p : new String[]{"TIKTOK", "LAZADA", "SHOPEE"}) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("platform", p);
            m.put("qty",      platQty.containsKey(p) ? platQty.get(p)[0] : 0L);
            m.put("gross",    platGross.getOrDefault(p, BigDecimal.ZERO));
            platforms.add(m);
        }

        @SuppressWarnings("unchecked")
        List<Object[]> topRows = entityManager.createNativeQuery(
                "SELECT oi.product_name, COALESCE(SUM(oi.quantity),0) AS qty, COALESCE(SUM(oi.subtotal),0) AS gross " +
                "FROM order_items oi JOIN orders o ON oi.order_id = o.id " +
                "LEFT JOIN products p ON p.id = oi.product_id " +
                "WHERE " + billed + " AND " + PIZZA_PRED + " " +
                "GROUP BY oi.product_name ORDER BY qty DESC LIMIT 5")
                .setParameter("start", start).setParameter("end", end)
                .getResultList();
        List<Map<String, Object>> top5 = topRows.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("productName", r[0]);
            m.put("qty",         ((Number) r[1]).longValue());
            m.put("gross",       bd(r[2]));
            return m;
        }).collect(Collectors.toList());

        // Pizza-vs-rest share of gross item sales
        BigDecimal allItemsGross = bd(entityManager.createNativeQuery(
                "SELECT COALESCE(SUM(oi.subtotal),0) FROM order_items oi JOIN orders o ON oi.order_id = o.id " +
                "WHERE " + billed)
                .setParameter("start", start).setParameter("end", end)
                .getSingleResult());
        BigDecimal restGross = allItemsGross.subtract(totalGross);
        BigDecimal pizzaSharePct = allItemsGross.signum() == 0 ? BigDecimal.ZERO
                : totalGross.multiply(BigDecimal.valueOf(100)).divide(allItemsGross, 1, RoundingMode.HALF_UP);

        Map<String, Object> pizza = new LinkedHashMap<>();
        pizza.put("totalQty",      totalQty);
        pizza.put("totalGross",    totalGross);
        pizza.put("directQty",     directQty);
        pizza.put("ecomQty",       ecomQty);
        pizza.put("directGross",   directGross);
        pizza.put("ecomGross",     ecomGross);
        pizza.put("platforms",     platforms);
        pizza.put("top5",          top5);
        pizza.put("restGross",     restGross);
        pizza.put("allItemsGross", allItemsGross);
        pizza.put("pizzaSharePct", pizzaSharePct);
        return pizza;
    }

    /** Category-based expense report (voided excluded), with sub-category detail, daily series,
     *  avg/high/low day, an "Uncategorized" remainder so categories tie to grandTotal, and the
     *  expense-to-net-revenue ratio. */
    private Map<String, Object> computeExpenses(YearMonth ym, LocalDate start, LocalDate end,
                                                BigDecimal netSales, BigDecimal grandTotal) {
        int year = ym.getYear(), month = ym.getMonthValue();

        List<Object[]> primRows = expenseRepository.sumByPrimaryCategoryForMonthWithCount(year, month);
        List<Object[]> subRows  = expenseRepository.sumBySubCategoryForMonthWithCount(year, month);
        List<Object[]> dayRows  = expenseRepository.sumByDayForDateRange(start, end);

        BigDecimal categorized = BigDecimal.ZERO;
        List<Map<String, Object>> byCategory = new ArrayList<>();
        for (Object[] r : primRows) {
            BigDecimal amt = bd(r[2]);
            categorized = categorized.add(amt);
            BigDecimal pct = grandTotal.signum() == 0 ? BigDecimal.ZERO
                    : amt.multiply(BigDecimal.valueOf(100)).divide(grandTotal, 1, RoundingMode.HALF_UP);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code",    r[0]);
            m.put("name",    r[1]);
            m.put("amount",  amt);
            m.put("entries", ((Number) r[3]).longValue());
            m.put("pct",     pct);
            byCategory.add(m);
        }
        BigDecimal uncategorized = grandTotal.subtract(categorized);
        if (uncategorized.signum() > 0) {
            BigDecimal pct = grandTotal.signum() == 0 ? BigDecimal.ZERO
                    : uncategorized.multiply(BigDecimal.valueOf(100)).divide(grandTotal, 1, RoundingMode.HALF_UP);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", "UNCATEGORIZED");
            m.put("name", "Uncategorized");
            m.put("amount", uncategorized);
            m.put("entries", 0L);
            m.put("pct", pct);
            byCategory.add(m);
        }

        List<Map<String, Object>> bySubcategory = new ArrayList<>();
        for (Object[] r : subRows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("primaryCode", r[0]);
            m.put("primaryName", r[1]);
            m.put("subName",     r[2]);
            m.put("amount",      bd(r[3]));
            m.put("entries",     ((Number) r[4]).longValue());
            bySubcategory.add(m);
        }

        List<Map<String, Object>> daily = new ArrayList<>();
        BigDecimal high = BigDecimal.ZERO, low = null;
        String highDay = null, lowDay = null;
        for (Object[] r : dayRows) {
            BigDecimal amt = bd(r[1]);
            String d = r[0].toString();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", d);
            m.put("amount", amt);
            daily.add(m);
            if (amt.compareTo(high) > 0) { high = amt; highDay = d; }
            if (low == null || amt.compareTo(low) < 0) { low = amt; lowDay = d; }
        }
        BigDecimal dailyAvg = grandTotal.divide(BigDecimal.valueOf(ym.lengthOfMonth()), 5, RoundingMode.HALF_UP);
        BigDecimal ratio = netSales.signum() == 0 ? BigDecimal.ZERO
                : grandTotal.multiply(BigDecimal.valueOf(100)).divide(netSales, 1, RoundingMode.HALF_UP);

        Map<String, Object> exp = new LinkedHashMap<>();
        exp.put("grandTotal",          grandTotal);
        exp.put("byCategory",          byCategory);
        exp.put("bySubcategory",       bySubcategory);
        exp.put("daily",               daily);
        exp.put("dailyAvg",            dailyAvg);
        exp.put("highestDay",          highDay);
        exp.put("highestDayAmount",    high);
        exp.put("lowestDay",           lowDay);
        exp.put("lowestDayAmount",     low == null ? BigDecimal.ZERO : low);
        exp.put("expenseToRevenuePct", ratio);
        return exp;
    }

    /** Month-over-month deltas across the headline metrics. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> buildMom(Map<String, Object> cur, Map<String, Object> prev, YearMonth prevYm) {
        Map<String, Object> cs = (Map<String, Object>) cur.get("summary");
        Map<String, Object> ps = (Map<String, Object>) prev.get("summary");
        Map<String, Object> cc = (Map<String, Object>) cur.get("channels");
        Map<String, Object> pc = (Map<String, Object>) prev.get("channels");
        Map<String, Object> cz = (Map<String, Object>) cur.get("pizza");
        Map<String, Object> pz = (Map<String, Object>) prev.get("pizza");

        List<Map<String, Object>> metrics = new ArrayList<>();
        metrics.add(delta("Total orders",      bd(cs.get("totalOrders")),   bd(ps.get("totalOrders"))));
        metrics.add(delta("Net revenue",       bd(cs.get("netRevenue")),    bd(ps.get("netRevenue"))));
        metrics.add(delta("Total expenses",    bd(cs.get("totalExpenses")), bd(ps.get("totalExpenses"))));
        metrics.add(delta("Net profit",        bd(cs.get("netProfit")),     bd(ps.get("netProfit"))));
        metrics.add(delta("Direct revenue",    bd(((Map<String, Object>) cc.get("direct")).get("netRevenue")),
                                               bd(((Map<String, Object>) pc.get("direct")).get("netRevenue"))));
        metrics.add(delta("Ecommerce revenue", bd(((Map<String, Object>) cc.get("ecommerce")).get("netRevenue")),
                                               bd(((Map<String, Object>) pc.get("ecommerce")).get("netRevenue"))));
        for (String p : new String[]{"TIKTOK", "LAZADA", "SHOPEE"}) {
            metrics.add(delta(p + " revenue", platNetOf(cc, p), platNetOf(pc, p)));
        }
        metrics.add(delta("Pizza box qty",   bd(cz.get("totalQty")),   bd(pz.get("totalQty"))));
        metrics.add(delta("Pizza box sales", bd(cz.get("totalGross")), bd(pz.get("totalGross"))));

        Map<String, Object> mom = new LinkedHashMap<>();
        mom.put("prevMonth", prevYm.toString());
        mom.put("metrics",   metrics);
        return mom;
    }

    @SuppressWarnings("unchecked")
    private BigDecimal platNetOf(Map<String, Object> channels, String platform) {
        Map<String, Object> ecom = (Map<String, Object>) channels.get("ecommerce");
        for (Map<String, Object> p : (List<Map<String, Object>>) ecom.get("platforms")) {
            if (platform.equals(p.get("platform"))) return bd(p.get("netRevenue"));
        }
        return BigDecimal.ZERO;
    }

    private Map<String, Object> delta(String label, BigDecimal cur, BigDecimal prev) {
        BigDecimal d = cur.subtract(prev);
        BigDecimal pct = prev.signum() == 0 ? null
                : d.multiply(BigDecimal.valueOf(100)).divide(prev.abs(), 1, RoundingMode.HALF_UP);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("metric",    label);
        m.put("current",   cur);
        m.put("previous",  prev);
        m.put("delta",     d);
        m.put("deltaPct",  pct);   // null when previous month is 0 (avoid divide-by-zero / infinite %)
        m.put("direction", d.signum() > 0 ? "up" : (d.signum() < 0 ? "down" : "flat"));
        return m;
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    /** Coerce a native-query numeric (BigDecimal / BigInteger / Long / Integer) to BigDecimal. */
    private static BigDecimal bd(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal b) return b;
        if (o instanceof java.math.BigInteger bi) return new BigDecimal(bi);
        if (o instanceof Number n) return BigDecimal.valueOf(n.longValue());
        return new BigDecimal(o.toString());
    }
}
