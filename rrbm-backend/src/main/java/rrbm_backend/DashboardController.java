package rrbm_backend;

import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Dashboard API endpoints.
 *
 * GET /api/dashboard/stats?period=daily|weekly|monthly
 *   totalSales        — sum of non-cancelled order totals for the period
 *   orderCount        — non-cancelled orders in period
 *   activeOrders      — live count of ACTIVE orders (always today-based)
 *   pendingOrders     — live count of PENDING orders (always today-based)
 *   cancelledOrders   — cancelled count for the period
 *   lowStockCount     — products at or below tag-based LOW threshold
 *   pizzaBoxQtyToday  — total qty of Pizza Box category items sold today
 *   paymentBreakdown  — { CASH: X, … } for the period (non-cancelled)
 *   salesTrend        — last 7 days bar chart data
 *
 * GET /api/dashboard/top-products-today
 *   Top 5 products by quantity sold today (non-cancelled orders)
 */
@RestController
@RequestMapping("/api/dashboard")
@CrossOrigin(origins = "*")
public class DashboardController {

    private final OrderRepository                orderRepository;
    private final ProductRepository              productRepository;
    private final DailyReportRepository          dailyReportRepository;
    private final ExpenseRepository              expenseRepository;
    private final PayableRepository              payableRepository;
    private final TransactionRepository          transactionRepository;
    private final CommissionPeriodRepository     commissionPeriodRepository;
    private final CommissionEntryRepository      commissionEntryRepository;
    private final CommissionAdjustmentRepository commissionAdjustmentRepository;

    public DashboardController(OrderRepository orderRepository,
                                ProductRepository productRepository,
                                DailyReportRepository dailyReportRepository,
                                ExpenseRepository expenseRepository,
                                PayableRepository payableRepository,
                                TransactionRepository transactionRepository,
                                CommissionPeriodRepository commissionPeriodRepository,
                                CommissionEntryRepository commissionEntryRepository,
                                CommissionAdjustmentRepository commissionAdjustmentRepository) {
        this.orderRepository              = orderRepository;
        this.productRepository            = productRepository;
        this.dailyReportRepository        = dailyReportRepository;
        this.expenseRepository            = expenseRepository;
        this.payableRepository            = payableRepository;
        this.transactionRepository        = transactionRepository;
        this.commissionPeriodRepository   = commissionPeriodRepository;
        this.commissionEntryRepository    = commissionEntryRepository;
        this.commissionAdjustmentRepository = commissionAdjustmentRepository;
    }

    // ── Helper: tag → low-stock threshold ─────────────────────────────────
    private static long lowThresholdForTag(String tag) {
        if (tag == null) return 0L;
        return switch (tag.toUpperCase()) {
            case "HOT"     -> 5000L;
            case "SELLING" -> 2000L;
            case "SLOW"    -> 1000L;
            default        -> 0L;
        };
    }

    @GetMapping("/stats")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> getStats(
            @RequestParam(defaultValue = "daily") String period) {

        LocalDate today = LocalDate.now();

        // ── Resolve period date range ─────────────────────────────────
        LocalDate periodStart = switch (period.toLowerCase()) {
            case "weekly"  -> today.with(DayOfWeek.MONDAY);
            case "monthly" -> today.withDayOfMonth(1);
            default        -> today; // daily
        };

        // ── Orders for the selected period ────────────────────────────
        List<Order> periodOrders = periodStart.equals(today)
                ? orderRepository.findByCreatedAtDate(today)
                : orderRepository.findByDateRange(periodStart, today);

        List<Order> billed = periodOrders.stream()
                .filter(o -> !"CANCELLED".equals(o.getStatus()))
                .collect(Collectors.toList());

        BigDecimal totalSales   = billed.stream()
                .map(o -> o.getTotal() != null ? o.getTotal() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long orderCount         = billed.size();
        long cancelledInPeriod  = periodOrders.stream()
                .filter(o -> "CANCELLED".equals(o.getStatus())).count();

        // ── Live active/pending counts (always today-based) ───────────
        List<Order> todayOrders = periodStart.equals(today)
                ? periodOrders
                : orderRepository.findByCreatedAtDate(today);
        List<Order> todayBilled = todayOrders.stream()
                .filter(o -> !"CANCELLED".equals(o.getStatus()))
                .collect(Collectors.toList());

        long activeOrders  = todayBilled.stream().filter(o -> "ACTIVE".equals(o.getStatus())).count();
        long pendingOrders = todayBilled.stream().filter(o -> "PENDING".equals(o.getStatus())).count();

        // ── Payment breakdown (period, non-cancelled) ─────────────────
        Map<String, BigDecimal> paymentBreakdown = new LinkedHashMap<>();
        billed.forEach(o -> paymentBreakdown.merge(
                o.getPaymentMode(),
                o.getTotal() != null ? o.getTotal() : BigDecimal.ZERO,
                BigDecimal::add));

        // ── Low stock (tag-based thresholds) ─────────────────────────
        List<Product> activeProducts = productRepository.findByActiveTrueOrderByNameAsc();

        List<Map<String, Object>> lowStockItems = activeProducts.stream()
                .filter(p -> {
                    long threshold = lowThresholdForTag(p.getSellingTag());
                    return threshold > 0 && p.getTotalStock() <= threshold;
                })
                .map(p -> {
                    long threshold = lowThresholdForTag(p.getSellingTag());
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id",        p.getId());
                    item.put("name",      p.getName());
                    item.put("tag",       p.getSellingTag());
                    item.put("stock",     p.getTotalStock());
                    item.put("threshold", threshold);
                    return item;
                })
                .collect(Collectors.toList());

        long lowStockCount = lowStockItems.size();

        // ── Pizza Box quota tracker (period-aware) ──────────────────
        List<Order> periodWithItems = periodStart.equals(today)
                ? orderRepository.findByCreatedAtDateWithItems(today)
                : orderRepository.findByDateRangeWithItems(periodStart, today);
        List<OrderItem> periodItems = periodWithItems.stream()
                .filter(o -> !"CANCELLED".equals(o.getStatus()))
                .flatMap(o -> o.getItems().stream())
                .collect(Collectors.toList());

        // Batch-load products to avoid N+1 queries
        Set<Long> productIds = periodItems.stream()
                .filter(i -> i.getProductId() != null)
                .map(OrderItem::getProductId)
                .collect(Collectors.toSet());
        Map<Long, String> productCategoryMap = productIds.isEmpty()
                ? Collections.emptyMap()
                : productRepository.findAllById(productIds).stream()
                        .filter(p -> p.getCategory() != null)
                        .collect(Collectors.toMap(Product::getId, p -> p.getCategory().toLowerCase()));

        long pizzaBoxQty = periodItems.stream()
                .filter(item -> {
                    if (item.getProductId() != null) {
                        String cat = productCategoryMap.getOrDefault(item.getProductId(), "");
                        return cat.contains("pizza box");
                    }
                    return item.getProductName() != null
                            && item.getProductName().toLowerCase().contains("pizza box");
                })
                .mapToLong(item -> item.getQuantity() != null ? item.getQuantity() : 0)
                .sum();

        // Pizza split: direct (non-ecommerce) vs ecommerce
        long directPizzaQty = periodWithItems.stream()
                .filter(o -> !"CANCELLED".equals(o.getStatus()) && !"ECOMMERCE".equals(o.getSource()))
                .flatMap(o -> o.getItems().stream())
                .filter(item -> {
                    if (item.getProductId() != null) {
                        String cat = productCategoryMap.getOrDefault(item.getProductId(), "");
                        return cat.contains("pizza box");
                    }
                    return item.getProductName() != null
                            && item.getProductName().toLowerCase().contains("pizza box");
                })
                .mapToLong(item -> item.getQuantity() != null ? item.getQuantity() : 0)
                .sum();
        long ecomPizzaQty = pizzaBoxQty - directPizzaQty;

        // Items sold in period
        long totalItemsSold = periodItems.stream()
                .mapToLong(item -> item.getQuantity() != null ? item.getQuantity() : 0)
                .sum();

        // ── Sales trend (period-aware) ────────────────────────────────
        BigDecimal todaySalesForTrend = todayBilled.stream()
                .map(o -> o.getTotal() != null ? o.getTotal() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<Map<String, Object>> salesTrend = new ArrayList<>();
        if (period.equalsIgnoreCase("weekly")) {
            LocalDate monday = today.with(DayOfWeek.MONDAY);
            List<DailyReport> weekReports = dailyReportRepository
                    .findByReportDateBetweenOrderByReportDateDesc(monday, today.minusDays(1));
            Map<LocalDate, BigDecimal> byDate = weekReports.stream()
                    .collect(Collectors.toMap(DailyReport::getReportDate,
                            r -> r.getTotalRevenue() != null ? r.getTotalRevenue() : BigDecimal.ZERO));
            for (int d = 0; d < 7; d++) {
                LocalDate date = monday.plusDays(d);
                BigDecimal dayTotal = date.equals(today) ? todaySalesForTrend
                        : byDate.getOrDefault(date, BigDecimal.ZERO);
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("date",  date.toString());
                point.put("label", date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH));
                point.put("total", dayTotal);
                salesTrend.add(point);
            }
        } else if (period.equalsIgnoreCase("monthly")) {
            LocalDate firstOfMonth = today.withDayOfMonth(1);
            List<DailyReport> monthReports = dailyReportRepository
                    .findByReportDateBetweenOrderByReportDateDesc(firstOfMonth, today.minusDays(1));
            Map<LocalDate, BigDecimal> byDate = monthReports.stream()
                    .collect(Collectors.toMap(DailyReport::getReportDate,
                            r -> r.getTotalRevenue() != null ? r.getTotalRevenue() : BigDecimal.ZERO));
            for (LocalDate date = firstOfMonth; !date.isAfter(today); date = date.plusDays(1)) {
                BigDecimal dayTotal = date.equals(today) ? todaySalesForTrend
                        : byDate.getOrDefault(date, BigDecimal.ZERO);
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("date",  date.toString());
                point.put("label", String.valueOf(date.getDayOfMonth()));
                point.put("total", dayTotal);
                salesTrend.add(point);
            }
        } else {
            // Daily: last 7 days
            LocalDate sevenDaysAgo = today.minusDays(6);
            List<DailyReport> reports = dailyReportRepository
                    .findByReportDateBetweenOrderByReportDateDesc(sevenDaysAgo, today.minusDays(1));
            Map<LocalDate, BigDecimal> reportByDate = reports.stream()
                    .collect(Collectors.toMap(DailyReport::getReportDate,
                            r -> r.getTotalRevenue() != null ? r.getTotalRevenue() : BigDecimal.ZERO));
            for (int i = 6; i >= 0; i--) {
                LocalDate date = today.minusDays(i);
                BigDecimal dayTotal = (i == 0) ? todaySalesForTrend
                        : reportByDate.getOrDefault(date, BigDecimal.ZERO);
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("date",  date.toString());
                point.put("label", date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH));
                point.put("total", dayTotal);
                salesTrend.add(point);
            }
        }

        // ── Total expenses (period-aware) ────────────────────────────
        List<Expense> periodExpenses = periodStart.equals(today)
                ? expenseRepository.findByDateWithItems(today)
                : expenseRepository.findByDateRangeWithItems(periodStart, today);
        BigDecimal totalExpenses = periodExpenses.stream()
                .map(e -> e.getTotalAmount() != null ? e.getTotalAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // ── Period date range for the frontend ─────────────────────────
        String periodStartStr = periodStart.toString();
        String periodEndStr   = today.toString();

        // ── Assemble response ─────────────────────────────────────────
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period",           period);
        result.put("periodStart",      periodStartStr);
        result.put("periodEnd",        periodEndStr);
        result.put("totalSales",       totalSales);
        result.put("orderCount",       orderCount);
        result.put("activeOrders",     activeOrders);
        result.put("pendingOrders",    pendingOrders);
        result.put("cancelledOrders",  cancelledInPeriod);
        result.put("lowStockCount",    lowStockCount);
        result.put("lowStockItems",    lowStockItems);
        result.put("totalItemsSold",     totalItemsSold);
        result.put("pizzaBoxQtyToday",   pizzaBoxQty);     // kept key for backward compat
        result.put("directPizzaQty",     directPizzaQty);
        result.put("ecomPizzaQty",       ecomPizzaQty);
        result.put("totalExpensesToday", totalExpenses);    // kept key for backward compat
        result.put("paymentBreakdown",   paymentBreakdown);
        result.put("salesTrend",       salesTrend);

        return ResponseEntity.ok(result);
    }

    // ── Top 5 products sold today ──────────────────────────────────────────
    @GetMapping("/top-products-today")
    @Transactional(readOnly = true)
    public ResponseEntity<List<Map<String, Object>>> getTopProductsToday() {
        LocalDate today  = LocalDate.now();
        List<Order> todayOrders = orderRepository.findByCreatedAtDateWithItems(today);

        // Aggregate by productName across non-cancelled orders
        Map<String, BigDecimal[]> agg = new LinkedHashMap<>();
        // agg[name] = [ totalQty, totalRevenue ]

        todayOrders.stream()
                .filter(o -> !"CANCELLED".equals(o.getStatus()))
                .flatMap(o -> o.getItems().stream())
                .forEach(item -> {
                    String name    = item.getProductName() != null ? item.getProductName() : "Unknown";
                    long qty       = item.getQuantity() != null ? item.getQuantity() : 0;
                    BigDecimal rev = item.getSubtotal() != null ? item.getSubtotal() : BigDecimal.ZERO;

                    agg.computeIfAbsent(name, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                    BigDecimal[] entry = agg.get(name);
                    entry[0] = entry[0].add(BigDecimal.valueOf(qty));
                    entry[1] = entry[1].add(rev);
                });

        // Sort by qty descending, take top 5, add rank
        List<Map<String, Object>> top5 = agg.entrySet().stream()
                .sorted((a, b) -> b.getValue()[0].compareTo(a.getValue()[0]))
                .limit(5)
                .collect(Collectors.toList())
                .stream()
                .map(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name",    e.getKey());
                    row.put("qty",     e.getValue()[0].longValue());
                    row.put("revenue", e.getValue()[1]);
                    return row;
                })
                .collect(Collectors.toList());

        for (int i = 0; i < top5.size(); i++) {
            top5.get(i).put("rank", i + 1);
        }

        return ResponseEntity.ok(top5);
    }

    // ── Channel summary: direct vs ecommerce, per-platform, payables ──────────
    @GetMapping("/channel-summary")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> getChannelSummary(
            @RequestParam(defaultValue = "daily") String period) {
        LocalDate today = LocalDate.now();
        LocalDate periodStart = switch (period.toLowerCase()) {
            case "weekly"  -> today.with(DayOfWeek.MONDAY);
            case "monthly" -> today.withDayOfMonth(1);
            default        -> today;
        };

        // ── Period orders (revenue / channel breakdown) ───────────────
        List<Order> periodWithItems = periodStart.equals(today)
                ? orderRepository.findByCreatedAtDateWithItems(today)
                : orderRepository.findByDateRangeWithItems(periodStart, today);
        List<Order> billedPeriod = periodWithItems.stream()
                .filter(o -> !"CANCELLED".equals(o.getStatus()))
                .collect(Collectors.toList());

        // Product → category map for pizza detection (batch load, no N+1)
        Set<Long> productIds = billedPeriod.stream()
                .flatMap(o -> o.getItems().stream())
                .filter(i -> i.getProductId() != null)
                .map(OrderItem::getProductId)
                .collect(Collectors.toSet());
        Map<Long, String> categoryMap = productIds.isEmpty()
                ? Collections.emptyMap()
                : productRepository.findAllById(productIds).stream()
                        .filter(p -> p.getCategory() != null)
                        .collect(Collectors.toMap(Product::getId,
                                p -> p.getCategory().toLowerCase(), (a, b) -> a));

        // Channel split
        List<Order> directOrders = billedPeriod.stream()
                .filter(o -> !"ECOMMERCE".equals(o.getSource()))
                .collect(Collectors.toList());
        List<Order> ecomOrders   = billedPeriod.stream()
                .filter(o -> "ECOMMERCE".equals(o.getSource()))
                .collect(Collectors.toList());

        BigDecimal directRevenue = directOrders.stream()
                .map(o -> o.getTotal() != null ? o.getTotal() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal ecomRevenue   = ecomOrders.stream()
                .map(o -> o.getTotal() != null ? o.getTotal() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        long directPizzaQty = pizzaQtyFromOrders(directOrders, categoryMap);
        long ecomPizzaQty   = pizzaQtyFromOrders(ecomOrders,   categoryMap);

        // Per-platform (Shopee / TikTok / Lazada)
        Map<String, List<Order>> byPlatform = ecomOrders.stream()
                .collect(Collectors.groupingBy(
                        o -> o.getEcommercePlatform() != null ? o.getEcommercePlatform() : "UNKNOWN"));

        List<Map<String, Object>> platforms = new ArrayList<>();
        for (String plat : new String[]{"SHOPEE", "TIKTOK", "LAZADA"}) {
            List<Order> platOrders = byPlatform.getOrDefault(plat, Collections.emptyList());
            BigDecimal platRevenue = platOrders.stream()
                    .map(o -> o.getTotal() != null ? o.getTotal() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("platform",   plat);
            pm.put("orderCount", platOrders.size());
            pm.put("revenue",    platRevenue);
            pm.put("pizzaQty",   pizzaQtyFromOrders(platOrders, categoryMap));
            platforms.add(pm);
        }

        // ── Live operational metrics (always today) ───────────────────
        List<Order> todayOrders = periodStart.equals(today)
                ? periodWithItems
                : orderRepository.findByCreatedAtDateWithItems(today);
        long codPending = todayOrders.stream()
                .filter(o -> "COD".equals(o.getPaymentMode())
                        && !List.of("DELIVERED", "CLOSED", "CANCELLED").contains(o.getStatus()))
                .count();

        // ── Payables (balance-sheet — no period filter) ───────────────
        BigDecimal payablesOutstanding  = payableRepository.getTotalOutstanding();
        long       payablesPendingCount = payableRepository.findByStatus("PENDING").size();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("directOrders",         directOrders.size());
        result.put("directRevenue",         directRevenue);
        result.put("directPizzaQty",        directPizzaQty);
        result.put("ecomOrders",            ecomOrders.size());
        result.put("ecomRevenue",           ecomRevenue);
        result.put("ecomPizzaQty",          ecomPizzaQty);
        result.put("ecomPlatforms",         platforms);
        result.put("codPending",            codPending);
        result.put("payablesOutstanding",   payablesOutstanding);
        result.put("payablesPendingCount",  payablesPendingCount);
        return ResponseEntity.ok(result);
    }

    // ── Product-analytics dashboard (category hierarchy, top products, trend) ──
    // GET /api/dashboard/product-analytics?period=daily|weekly|monthly
    // daily = live today; weekly/monthly = CLOSED days only.
    @GetMapping("/product-analytics")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> getProductAnalytics(
            @RequestParam(defaultValue = "daily") String period) {

        LocalDate today = LocalDate.now();
        LocalDate periodStart = switch (period.toLowerCase()) {
            case "weekly"  -> today.with(DayOfWeek.MONDAY);
            case "monthly" -> today.withDayOfMonth(1);
            default        -> today;
        };

        // Resolve the set of dates whose data should count.
        // daily → today only (live). weekly/monthly → only CLOSED days.
        Set<LocalDate> allowedDates;
        List<Order> periodOrders;
        if (periodStart.equals(today)) {
            allowedDates = Set.of(today);
            periodOrders = orderRepository.findByCreatedAtDateWithItems(today);
        } else {
            allowedDates = dailyReportRepository
                    .findByReportDateBetweenOrderByReportDateDesc(periodStart, today)
                    .stream().map(DailyReport::getReportDate).collect(Collectors.toSet());
            periodOrders = allowedDates.isEmpty()
                    ? Collections.emptyList()
                    : orderRepository.findByDateRangeWithItems(periodStart, today).stream()
                        .filter(o -> o.getCreatedAt() != null
                                && allowedDates.contains(o.getCreatedAt().toLocalDate()))
                        .collect(Collectors.toList());
        }

        List<Order> billed = periodOrders.stream()
                .filter(o -> !"CANCELLED".equals(o.getStatus()))
                .collect(Collectors.toList());

        // Batch-load products for category lookup
        Set<Long> productIds = billed.stream()
                .flatMap(o -> o.getItems().stream())
                .filter(i -> i.getProductId() != null)
                .map(OrderItem::getProductId)
                .collect(Collectors.toSet());
        Map<Long, Product> productMap = productIds.isEmpty()
                ? Collections.emptyMap()
                : productRepository.findAllById(productIds).stream()
                        .collect(Collectors.toMap(Product::getId, p -> p, (a, b) -> a));

        // Aggregate quantities
        long totalQtySold = 0L;
        long pizzaBoxQty  = 0L;
        Map<String, Long> productQty   = new HashMap<>();           // productName → qty
        Map<String, Map<String, Long>> catSub = new LinkedHashMap<>(); // category → (subcat → qty)

        for (Order o : billed) {
            for (OrderItem item : o.getItems()) {
                long qty = item.getQuantity() != null ? item.getQuantity() : 0;
                if (qty == 0) continue;
                totalQtySold += qty;

                Product p = item.getProductId() != null ? productMap.get(item.getProductId()) : null;
                String[] cls = classifyProduct(p, item.getProductName());
                String cat = cls[0], sub = cls[1];

                if ("Pizza Box".equals(cat)) pizzaBoxQty += qty;

                catSub.computeIfAbsent(cat, k -> new LinkedHashMap<>())
                      .merge(sub, qty, Long::sum);

                String name = item.getProductName() != null ? item.getProductName() : "Unknown";
                productQty.merge(name, qty, Long::sum);
            }
        }

        // Build category list with contribution % + rank
        final long totalForPct = totalQtySold;
        List<Map<String, Object>> categories = new ArrayList<>();
        catSub.entrySet().stream()
              .sorted((a, b) -> Long.compare(
                      b.getValue().values().stream().mapToLong(Long::longValue).sum(),
                      a.getValue().values().stream().mapToLong(Long::longValue).sum()))
              .forEach(e -> {
                  long catQty = e.getValue().values().stream().mapToLong(Long::longValue).sum();
                  List<Map<String, Object>> subs = e.getValue().entrySet().stream()
                          .sorted((x, y) -> Long.compare(y.getValue(), x.getValue()))
                          .map(s -> {
                              Map<String, Object> sm = new LinkedHashMap<>();
                              sm.put("name", s.getKey());
                              sm.put("qty", s.getValue());
                              return sm;
                          }).collect(Collectors.toList());
                  Map<String, Object> cm = new LinkedHashMap<>();
                  cm.put("name", e.getKey());
                  cm.put("qty", catQty);
                  cm.put("pct", totalForPct > 0 ? Math.round(catQty * 1000.0 / totalForPct) / 10.0 : 0.0);
                  cm.put("subcategories", subs);
                  categories.add(cm);
              });
        for (int i = 0; i < categories.size(); i++) categories.get(i).put("rank", i + 1);

        // Top N products by qty (10 for monthly, 5 otherwise)
        int topProductsLimit = "monthly".equalsIgnoreCase(period) ? 10 : 5;
        List<Map<String, Object>> topProducts = productQty.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(topProductsLimit)
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", e.getKey());
                    m.put("qty", e.getValue());
                    return m;
                }).collect(Collectors.toList());

        // Pizza quota target by period
        Double target;
        switch (period.toLowerCase()) {
            case "weekly"  -> target = 30000.0;
            case "monthly" -> target = previousMonthPizzaTotal(today);
            default        -> target = 5000.0;
        }
        Map<String, Object> pizzaQuota = new LinkedHashMap<>();
        pizzaQuota.put("target", target);
        pizzaQuota.put("actual", pizzaBoxQty);
        pizzaQuota.put("pct", (target != null && target > 0)
                ? Math.round(pizzaBoxQty * 1000.0 / target) / 10.0 : null);

        // Trend series
        List<Map<String, Object>> trend = buildTrend(period, periodStart, today, billed, allowedDates);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period", period);
        result.put("periodStart", periodStart.toString());
        result.put("periodEnd", today.toString());
        result.put("totalQtySold", totalQtySold);
        result.put("pizzaQuota", pizzaQuota);
        result.put("categories", categories);
        result.put("topProducts", topProducts);
        result.put("trend", trend);
        return ResponseEntity.ok(result);
    }

    /**
     * Classify a product into [dashboardCategory, subcategory] using the existing
     * inventory category/sub_category fields. Falls back to name keywords if product missing.
     */
    private String[] classifyProduct(Product p, String fallbackName) {
        String cat = p != null && p.getCategory() != null ? p.getCategory().trim() : "";
        String sub = p != null && p.getSubCategory() != null ? p.getSubCategory().trim() : "";
        String name = fallbackName != null ? fallbackName.toLowerCase() : "";

        if (cat.equalsIgnoreCase("Pizza Box") || (cat.isEmpty() && name.contains("pizza box"))) {
            return new String[]{"Pizza Box", sub.isEmpty() ? "Pizza Boxes" : sub};
        }
        if (cat.equalsIgnoreCase("Pizza Supplies")) {
            return new String[]{"Pizza Box Supplies", sub.isEmpty() ? "Other" : sub};
        }
        if (cat.equalsIgnoreCase("Die-Cut Packaging Boxes") || cat.equalsIgnoreCase("Die Cut Packaging Boxes")) {
            return new String[]{"Die Cut Packaging Boxes", sub.isEmpty() ? "Other" : sub};
        }
        if (cat.equalsIgnoreCase("Offset Packaging Boxes")) {
            return new String[]{"Offset Packaging Boxes", sub.isEmpty() ? "Pastry Boxes" : sub};
        }
        // Packaging Supplies, RSC, anything else
        return new String[]{"Packaging Supplies", sub.isEmpty() ? "Other" : sub};
    }

    /** Previous-month closed pizza-box total; null if no data. */
    private Double previousMonthPizzaTotal(LocalDate today) {
        LocalDate prevMonthStart = today.minusMonths(1).withDayOfMonth(1);
        LocalDate prevMonthEnd   = today.withDayOfMonth(1).minusDays(1);
        Set<LocalDate> closed = dailyReportRepository
                .findByReportDateBetweenOrderByReportDateDesc(prevMonthStart, prevMonthEnd)
                .stream().map(DailyReport::getReportDate).collect(Collectors.toSet());
        if (closed.isEmpty()) return null;

        List<Order> orders = orderRepository.findByDateRangeWithItems(prevMonthStart, prevMonthEnd).stream()
                .filter(o -> !"CANCELLED".equals(o.getStatus())
                        && o.getCreatedAt() != null
                        && closed.contains(o.getCreatedAt().toLocalDate()))
                .collect(Collectors.toList());
        if (orders.isEmpty()) return null;

        Set<Long> pids = orders.stream().flatMap(o -> o.getItems().stream())
                .filter(i -> i.getProductId() != null).map(OrderItem::getProductId)
                .collect(Collectors.toSet());
        Map<Long, Product> pm = pids.isEmpty() ? Collections.emptyMap()
                : productRepository.findAllById(pids).stream()
                        .collect(Collectors.toMap(Product::getId, p -> p, (a, b) -> a));

        long total = 0L;
        for (Order o : orders) {
            for (OrderItem it : o.getItems()) {
                Product p = it.getProductId() != null ? pm.get(it.getProductId()) : null;
                if ("Pizza Box".equals(classifyProduct(p, it.getProductName())[0])) {
                    total += it.getQuantity() != null ? it.getQuantity() : 0;
                }
            }
        }
        return total > 0 ? (double) total : null;
    }

    /** Build the trend series: daily=hourly, weekly=Mon-Sun, monthly=per-date. */
    private List<Map<String, Object>> buildTrend(String period, LocalDate periodStart,
            LocalDate today, List<Order> billed, Set<LocalDate> allowedDates) {
        List<Map<String, Object>> trend = new ArrayList<>();

        if ("weekly".equalsIgnoreCase(period)) {
            for (int d = 0; d < 7; d++) {
                LocalDate date = periodStart.plusDays(d);
                long qty = allowedDates.contains(date) ? qtyForDate(billed, date) : 0L;
                Map<String, Object> pt = new LinkedHashMap<>();
                pt.put("label", date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH));
                pt.put("qty", qty);
                trend.add(pt);
            }
        } else if ("monthly".equalsIgnoreCase(period)) {
            for (LocalDate date = periodStart; !date.isAfter(today); date = date.plusDays(1)) {
                long qty = allowedDates.contains(date) ? qtyForDate(billed, date) : 0L;
                Map<String, Object> pt = new LinkedHashMap<>();
                pt.put("label", String.valueOf(date.getDayOfMonth()));
                pt.put("qty", qty);
                trend.add(pt);
            }
        } else {
            // daily → 24 hour buckets
            long[] hours = new long[24];
            for (Order o : billed) {
                if (o.getCreatedAt() == null) continue;
                int h = o.getCreatedAt().getHour();
                long oQty = o.getItems().stream()
                        .mapToLong(i -> i.getQuantity() != null ? i.getQuantity() : 0).sum();
                hours[h] += oQty;
            }
            for (int h = 0; h < 24; h++) {
                Map<String, Object> pt = new LinkedHashMap<>();
                pt.put("label", String.format("%02d:00", h));
                pt.put("qty", hours[h]);
                trend.add(pt);
            }
        }
        return trend;
    }

    private long qtyForDate(List<Order> billed, LocalDate date) {
        return billed.stream()
                .filter(o -> o.getCreatedAt() != null && o.getCreatedAt().toLocalDate().equals(date))
                .flatMap(o -> o.getItems().stream())
                .mapToLong(i -> i.getQuantity() != null ? i.getQuantity() : 0)
                .sum();
    }

    // Helper: sum pizza box qty from a list of orders using the category map
    private long pizzaQtyFromOrders(List<Order> orders, Map<Long, String> categoryMap) {
        return orders.stream()
                .flatMap(o -> o.getItems().stream())
                .filter(item -> {
                    if (item.getProductId() != null) {
                        return categoryMap.getOrDefault(item.getProductId(), "").contains("pizza box");
                    }
                    return item.getProductName() != null
                            && item.getProductName().toLowerCase().contains("pizza box");
                })
                .mapToLong(item -> item.getQuantity() != null ? item.getQuantity() : 0)
                .sum();
    }

    // ── Cash-flow widget ─────────────────────────────────────────────────────────
    // GET /api/dashboard/cashflow?year=YYYY&month=M
    //   revenue     — net of SALE and RETURN transactions for the month
    //   expenses    — non-voided expenses for the month
    //   commissions — netCommission of RELEASED periods overlapping the month
    //   net         — revenue − expenses − commissions

    @GetMapping("/cashflow")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> getCashFlow(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month) {

        LocalDate today = LocalDate.now();
        int y = (year  != null) ? year  : today.getYear();
        int m = (month != null) ? month : today.getMonthValue();

        LocalDate firstDay = LocalDate.of(y, m, 1);
        LocalDate lastDay  = firstDay.withDayOfMonth(firstDay.lengthOfMonth());

        // Revenue: SALE (positive) + RETURN (stored negative) = net
        BigDecimal revenue = nullToZero(
                transactionRepository.sumSaleNetForDateRange(firstDay, lastDay));

        // Expenses: non-voided expenses in the month
        BigDecimal expenses = nullToZero(
                expenseRepository.sumNonVoidedForDateRange(firstDay, lastDay));

        // Commissions: sum netCommission across RELEASED periods overlapping the month
        List<CommissionPeriod> overlapping = commissionPeriodRepository
                .findByStartDateLessThanEqualAndEndDateGreaterThanEqual(lastDay, firstDay);
        List<Long> releasedPeriodIds = overlapping.stream()
                .filter(p -> "RELEASED".equals(p.getStatus()))
                .map(CommissionPeriod::getId)
                .collect(Collectors.toList());

        BigDecimal commissions = BigDecimal.ZERO;
        if (!releasedPeriodIds.isEmpty()) {
            BigDecimal entrySum = nullToZero(
                    commissionEntryRepository.sumReleasedOpAmountForPeriods(releasedPeriodIds));
            BigDecimal adjSum = nullToZero(
                    commissionAdjustmentRepository.sumNetAdjustmentsForPeriods(releasedPeriodIds));
            commissions = entrySum.add(adjSum);
        }

        BigDecimal net = revenue.subtract(expenses).subtract(commissions);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("year",        y);
        result.put("month",       m);
        result.put("revenue",     revenue);
        result.put("expenses",    expenses);
        result.put("commissions", commissions);
        result.put("net",         net);

        return ResponseEntity.ok(result);
    }

    private static BigDecimal nullToZero(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
