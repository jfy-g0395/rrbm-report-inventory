package rrbm_backend;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * CSV import pipeline.
 *
 * POST /api/import/authorize           — permission gate
 * GET  /api/import/template/sales      — blank sales CSV template (flat format)
 * GET  /api/import/template/expenses   — blank expenses CSV template
 * POST /api/import/upload/sales        — parse + validate sales CSV; returns preview (no DB writes)
 * POST /api/import/upload/expenses     — parse + validate expenses CSV; returns preview (no DB writes)
 * POST /api/import/upload/combined     — parse + validate both sales + expenses CSVs (no DB writes)
 * POST /api/import/validate            — pre-commit stock + report-close check (no DB writes)
 * POST /api/import/commit              — write validated rows to DB
 * POST /api/import/close               — close daily reports for committed dates
 */
@RestController
@RequestMapping("/api/import")
public class ImportController {

    private static final Logger log = LoggerFactory.getLogger(ImportController.class);

    // ── Constants ─────────────────────────────────────────────────────────────

    private static final Set<String> UPLOAD_ROLES =
            Set.of("ACCOUNTING", "ADMIN", "ADMINISTRATOR", "SUPER_ADMIN");

    private static final Set<String> VALID_SOURCES =
            Set.of("WALK_IN", "AGENT", "ECOMMERCE", "FACEBOOK_PAGE", "RESELLER", "DISTRIBUTOR");

    private static final Set<String> VALID_PAYMENT_METHODS =
            Set.of("CASH", "BANK_TRANSFER", "GCASH", "PAYMAYA", "COD");

    private static final Set<String> VALID_PLATFORMS =
            Set.of("SHOPEE", "TIKTOK", "LAZADA");

    private static final long SESSION_TTL_MS = 30L * 60 * 1000;

    // ── In-memory session store ────────────────────────────────────────────────

    private static final ConcurrentHashMap<String, ImportSession> SESSIONS =
            new ConcurrentHashMap<>();

    // ── Session data records ───────────────────────────────────────────────────

    private record ParsedItemRow(
            String itemCode, Long productId, String productName,
            int qty, BigDecimal unitPrice, BigDecimal basePrice, BigDecimal opPerUnit) {}

    private record ParsedSaleRow(
            String receiptNum, LocalDate date, String customer, String source,
            String agentName, Long agentId, String paymentMethod, BigDecimal total,
            String ecommercePlatform, String paymentStatus,
            List<ParsedItemRow> items) {}

    private record ParsedExpenseRow(
            LocalDate date, Long categoryId, Long subCategoryId,
            BigDecimal amount, String notes, String paymentMethod,
            String referenceNumber) {}

    private record ImportSession(
            List<ParsedSaleRow> validSales,
            List<ParsedExpenseRow> validExpenses,
            long expiresMs) {}

    // ── Expense category fuzzy-match map ──────────────────────────────────────
    // Keys are lowercase substrings; values are {categoryCode, subCategoryName}.
    // Checked only when the user's category input fails an exact code lookup.

    private static final Map<String, String[]> FUZZY_CAT;
    static {
        FUZZY_CAT = new LinkedHashMap<>();
        FUZZY_CAT.put("gas",          new String[]{"OPERATIONS", "Gas Allowance"});
        FUZZY_CAT.put("fuel",         new String[]{"OPERATIONS", "Fuel Reimbursement"});
        FUZZY_CAT.put("petrol",       new String[]{"OPERATIONS", "Fuel Reimbursement"});
        FUZZY_CAT.put("water",        new String[]{"UTILITY",    "Water Utility Bill"});
        FUZZY_CAT.put("electric",     new String[]{"UTILITY",    "Electric Bill"});
        FUZZY_CAT.put("meralco",      new String[]{"UTILITY",    "Electric Bill"});
        FUZZY_CAT.put("internet",     new String[]{"UTILITY",    "Internet Bill (ISP)"});
        FUZZY_CAT.put("wifi",         new String[]{"UTILITY",    "Internet Bill (ISP)"});
        FUZZY_CAT.put("converge",     new String[]{"UTILITY",    "Internet Bill (ISP)"});
        FUZZY_CAT.put("pldt",         new String[]{"UTILITY",    "Internet Bill (ISP)"});
        FUZZY_CAT.put("rent",         new String[]{"FACILITY",   "Monthly Office Rent"});
        FUZZY_CAT.put("lease",        new String[]{"FACILITY",   "Monthly Office Rent"});
        FUZZY_CAT.put("maintenance",  new String[]{"FACILITY",   "Building Maintenance"});
        FUZZY_CAT.put("repair",       new String[]{"FACILITY",   "Building Maintenance"});
        FUZZY_CAT.put("salary",       new String[]{"PERSONNEL",  "Employee Salary"});
        FUZZY_CAT.put("wage",         new String[]{"PERSONNEL",  "Employee Salary"});
        FUZZY_CAT.put("food",         new String[]{"PERSONNEL",  "Daily Food Expense"});
        FUZZY_CAT.put("meal",         new String[]{"PERSONNEL",  "Daily Food Expense"});
        FUZZY_CAT.put("overtime",     new String[]{"PERSONNEL",  "Overtime Pay"});
        FUZZY_CAT.put("bonus",        new String[]{"PERSONNEL",  "Bonuses/Incentives"});
        FUZZY_CAT.put("incentive",    new String[]{"PERSONNEL",  "Bonuses/Incentives"});
        FUZZY_CAT.put("delivery",     new String[]{"OPERATIONS", "Delivery Budget"});
        FUZZY_CAT.put("shipping",     new String[]{"OPERATIONS", "Shipping Fee"});
        FUZZY_CAT.put("parking",      new String[]{"OPERATIONS", "Parking Fee"});
        FUZZY_CAT.put("checker",      new String[]{"OPERATIONS", "Checker Fee"});
        FUZZY_CAT.put("packaging",    new String[]{"INVENTORY",  "Packaging Tapes"});
        FUZZY_CAT.put("tape",         new String[]{"INVENTORY",  "Packaging Tapes"});
        FUZZY_CAT.put("bubble",       new String[]{"INVENTORY",  "Bubble Wrap"});
        FUZZY_CAT.put("box",          new String[]{"INVENTORY",  "Boxes/Cartons"});
        FUZZY_CAT.put("carton",       new String[]{"INVENTORY",  "Boxes/Cartons"});
        FUZZY_CAT.put("sticker",      new String[]{"INVENTORY",  "Stickers/Labels"});
        FUZZY_CAT.put("label",        new String[]{"INVENTORY",  "Stickers/Labels"});
        FUZZY_CAT.put("software",     new String[]{"SERVICES",   "Software/Subscriptions"});
        FUZZY_CAT.put("subscription", new String[]{"SERVICES",   "Software/Subscriptions"});
        FUZZY_CAT.put("bank",         new String[]{"MISC",       "Bank Charges"});
        FUZZY_CAT.put("petty",        new String[]{"MISC",       "Petty Cash"});
        FUZZY_CAT.put("supplies",     new String[]{"SUPPLY",     "Office Supplies"});
        FUZZY_CAT.put("cleaning",     new String[]{"SUPPLY",     "Cleaning Supplies"});
    }

    /** Returns {categoryCode, subCategoryName} if input fuzzy-matches a known category; else null. */
    private String[] fuzzyMatchCategory(String input) {
        if (input == null) return null;
        String lower = input.toLowerCase().trim();
        for (Map.Entry<String, String[]> e : FUZZY_CAT.entrySet()) {
            if (lower.contains(e.getKey())) return e.getValue();
        }
        return null;
    }

    // ── CSV parse helpers ──────────────────────────────────────────────────────

    private String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (char c : line.toCharArray()) {
            if (c == '"') { inQuotes = !inQuotes; }
            else if (c == ',' && !inQuotes) { fields.add(sb.toString().trim()); sb.setLength(0); }
            else { sb.append(c); }
        }
        fields.add(sb.toString().trim());
        return fields.toArray(new String[0]);
    }

    private String getCol(String[] cols, int idx, String def) {
        if (cols == null || idx >= cols.length) return def;
        String v = cols[idx];
        return v == null ? def : v.trim();
    }

    // ── Flexible date parser ──────────────────────────────────────────────────
    // Accepts YYYY-MM-DD, M/D/YYYY, MM/DD/YYYY, d/M/yyyy, dd-MM-yyyy, M/d/yy,
    // "7 Jun 2026", "June 7, 2026" — any format Excel is likely to produce.

    private static final DateTimeFormatter[] DATE_FORMATS = {
            DateTimeFormatter.ISO_LOCAL_DATE,            // 2026-06-07
            DateTimeFormatter.ofPattern("M/d/yyyy"),     // 6/7/2026
            DateTimeFormatter.ofPattern("MM/dd/yyyy"),   // 06/07/2026
            DateTimeFormatter.ofPattern("d/M/yyyy"),     // 7/6/2026
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),   // 07-06-2026
            DateTimeFormatter.ofPattern("M/d/yy"),       // 6/7/26
            DateTimeFormatter.ofPattern("d MMM yyyy"),   // 7 Jun 2026
            DateTimeFormatter.ofPattern("MMMM d, yyyy"), // June 7, 2026
    };

    private LocalDate parseDate(String s) {
        if (s == null || s.isBlank())
            throw new IllegalArgumentException("Date is required");
        String c = s.trim();
        for (DateTimeFormatter fmt : DATE_FORMATS) {
            try { return LocalDate.parse(c, fmt); } catch (Exception ignored) {}
        }
        throw new IllegalArgumentException(
                "Cannot parse date '" + s + "' — use YYYY-MM-DD, M/D/YYYY, or MM/DD/YYYY");
    }

    // ── Template CSVs ──────────────────────────────────────────────────────────

    // Flat sales template: one row per item; same Receipt# on multiple rows = one order.
    // Source is optional — inferred from Agent column (AGENT if Agent filled, WALK_IN if both blank).
    // OP per Unit is only required for AGENT orders; leave blank for regular sales.
    // Total is computed automatically from Qty × Unit Price — do not add a Total column.
    private static final String SALES_TEMPLATE_CSV =
            "Date,Receipt#,Time,Customer,Source,Agent,Payment Method,Platform,Product Code,Qty,Unit Price,OP per Unit,Payment Status\r\n";

    private static final String EXPENSES_TEMPLATE_CSV =
            "Date,Category,Sub-Category,Amount,Notes,Payment Method,Reference\r\n";

    // ── Dependencies ──────────────────────────────────────────────────────────

    private final UserRepository            userRepository;
    private final JwtUtil                   jwtUtil;
    private final AgentRepository           agentRepository;
    private final ExpenseCategoryRepository categoryRepository;
    private final ProductRepository         productRepository;
    private final OrderService              orderService;
    private final ExpenseRepository         expenseRepository;
    private final OrderRepository           orderRepository;
    private final DailyReportRepository     dailyReportRepository;
    private final DailyReportService        dailyReportService;
    private final ActivityLogService        activityLogService;
    private final CommissionService              commissionService;
    private final TransactionService             transactionService;
    private final InventoryService               inventoryService;
    private final ProductSetComponentRepository   productSetComponentRepository;
    private final ImportCommitLogRepository       importCommitLogRepository;
    private final BCryptPasswordEncoder          passwordEncoder = new BCryptPasswordEncoder();

    public ImportController(UserRepository userRepository,
                            JwtUtil jwtUtil,
                            AgentRepository agentRepository,
                            ExpenseCategoryRepository categoryRepository,
                            ProductRepository productRepository,
                            OrderService orderService,
                            ExpenseRepository expenseRepository,
                            OrderRepository orderRepository,
                            DailyReportRepository dailyReportRepository,
                            DailyReportService dailyReportService,
                            ActivityLogService activityLogService,
                            CommissionService commissionService,
                            TransactionService transactionService,
                            InventoryService inventoryService,
                            ProductSetComponentRepository productSetComponentRepository,
                            ImportCommitLogRepository importCommitLogRepository) {
        this.userRepository        = userRepository;
        this.jwtUtil               = jwtUtil;
        this.agentRepository       = agentRepository;
        this.categoryRepository    = categoryRepository;
        this.productRepository     = productRepository;
        this.orderService          = orderService;
        this.expenseRepository     = expenseRepository;
        this.orderRepository       = orderRepository;
        this.dailyReportRepository = dailyReportRepository;
        this.dailyReportService    = dailyReportService;
        this.activityLogService    = activityLogService;
        this.commissionService              = commissionService;
        this.transactionService             = transactionService;
        this.inventoryService               = inventoryService;
        this.productSetComponentRepository  = productSetComponentRepository;
        this.importCommitLogRepository      = importCommitLogRepository;
    }

    private Long userIdFromHeader(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return null;
        return jwtUtil.extractUserId(authHeader.substring(7));
    }

    private User resolveUploadUser(String authHeader) {
        Long userId = userIdFromHeader(authHeader);
        if (userId == null) return null;
        return userRepository.findById(userId).orElse(null);
    }

    private ResponseEntity<?> checkRole(User user) {
        if (!UPLOAD_ROLES.contains(user.getRole())) {
            return ResponseEntity.status(403).body(Map.of("error",
                    "Upload permission requires ACCOUNTING or ADMIN role"));
        }
        return null;
    }

    private ResponseEntity<?> checkKey(User user, String rawKey) {
        if (user.getAdminSecurityKey() == null
                || !passwordEncoder.matches(rawKey.trim(), user.getAdminSecurityKey())) {
            return ResponseEntity.status(403).body(Map.of("error", "Invalid security key"));
        }
        return null;
    }

    // ── POST /api/import/authorize ────────────────────────────────────────────

    @PostMapping("/authorize")
    public ResponseEntity<?> authorizeImport(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, Object> body) {

        Long userId = userIdFromHeader(authHeader);
        if (userId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        User user = userRepository.findById(userId).orElse(null);
        if (user == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        ResponseEntity<?> roleErr = checkRole(user);
        if (roleErr != null) return roleErr;

        String rawKey = body.getOrDefault("adminSecurityKey", "").toString();
        ResponseEntity<?> keyErr = checkKey(user, rawKey);
        if (keyErr != null) return keyErr;

        return ResponseEntity.ok(Map.of("authorized", true));
    }

    // ── GET /api/import/template/sales ────────────────────────────────────────

    @GetMapping("/template/sales")
    public ResponseEntity<byte[]> downloadSalesTemplate(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        Long userId = userIdFromHeader(authHeader);
        if (userId == null) return ResponseEntity.status(401).build();

        byte[] bytes = SALES_TEMPLATE_CSV.getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"rrbm-sales-template.csv\"")
                .body(bytes);
    }

    // ── GET /api/import/template/expenses ─────────────────────────────────────

    @GetMapping("/template/expenses")
    public ResponseEntity<byte[]> downloadExpensesTemplate(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        Long userId = userIdFromHeader(authHeader);
        if (userId == null) return ResponseEntity.status(401).build();

        byte[] bytes = EXPENSES_TEMPLATE_CSV.getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"rrbm-expenses-template.csv\"")
                .body(bytes);
    }

    // ── POST /api/import/upload/sales ─────────────────────────────────────────

    /**
     * Parse and validate a flat sales CSV. Each row is one line item.
     * Rows sharing the same Receipt# are grouped into one order automatically —
     * they do not need to be adjacent in the file.
     *
     * Column layout (0-based):
     *   0=Date  1=Receipt#  2=Time  3=Customer  4=Source  5=Agent
     *   6=Payment Method  7=Platform  8=Product Code  9=Qty  10=Unit Price
      *   11=Base Price  12=OP per Unit  13=Payment Status (optional — PAID or UNPAID)
     *
     * Source inference: if Source is blank, defaults to AGENT when Agent is filled,
     * otherwise defaults to WALK_IN.
     *
     * Total is computed from Qty × Unit Price — not read from the file.
     *
     * Returns: { sessionToken, valid[], needsFix[], duplicates[], summary }
     * Does NOT write to the database.
     */
    @PostMapping("/upload/sales")
    public ResponseEntity<?> uploadSales(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam("file") MultipartFile file,
            @RequestParam("adminSecurityKey") String adminSecurityKey) {

        User user = resolveUploadUser(authHeader);
        if (user == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        ResponseEntity<?> roleErr = checkRole(user);
        if (roleErr != null) return roleErr;

        ResponseEntity<?> keyErr = checkKey(user, adminSecurityKey);
        if (keyErr != null) return keyErr;

        String content;
        try {
            content = new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Failed to read uploaded file"));
        }

        // Pre-load agent lookup once — avoids a DB query per row
        Map<String, Long> activeAgentsByName = agentRepository
                .findByStatusOrderByFullNameAsc("ACTIVE").stream()
                .collect(Collectors.toMap(
                        a -> a.getFullName().toLowerCase(),
                        Agent::getId,
                        (a, b) -> a));

        List<Map<String, Object>> valid       = new ArrayList<>();
        List<Map<String, Object>> needsFix    = new ArrayList<>();
        List<Map<String, Object>> duplicates  = new ArrayList<>();
        List<ParsedSaleRow>       validSales  = new ArrayList<>();

        // Keyed by receipt# — preserves file order via LinkedHashMap
        Map<String, SaleAccumulator> salesMap         = new LinkedHashMap<>();
        Set<String>                  invalidReceipts  = new HashSet<>();
        Set<String>                  duplicateReceipts = new HashSet<>();

        String[] lines = content.split("\\r?\\n");
        boolean headerSkipped = false;
        int rowIdx = 0;

        for (String rawLine : lines) {
            String trimmed = rawLine.trim();
            if (trimmed.isEmpty()) continue;
            if (!headerSkipped) { headerSkipped = true; continue; }

            rowIdx++;
            String[] cols     = parseCsvLine(trimmed);
            String receiptNum = getCol(cols, 1, "");

            if (receiptNum.isBlank()) {
                Map<String, Object> fix = new LinkedHashMap<>();
                fix.put("rowIndex", rowIdx);
                fix.put("receiptNum", "(blank)");
                fix.put("errors", List.of("Receipt# is required"));
                needsFix.add(fix);
                continue;
            }

            // ── First time seeing this receipt# — validate order-level fields ──
            boolean alreadyTracked = salesMap.containsKey(receiptNum)
                    || invalidReceipts.contains(receiptNum)
                    || duplicateReceipts.contains(receiptNum);

            if (!alreadyTracked) {
                List<String> errors = new ArrayList<>();

                String dateStr     = getCol(cols, 0, "");
                String time        = getCol(cols, 2, "");
                String customer    = getCol(cols, 3, "");
                String sourceRaw   = getCol(cols, 4, "").toUpperCase();
                String agentName   = getCol(cols, 5, "");
                String paymentMeth   = getCol(cols, 6, "").toUpperCase();
                String platformRaw   = getCol(cols, 7, "").toUpperCase();
                String paymentStatus = getCol(cols, 13, "").toUpperCase();

                LocalDate date = null;
                try { date = parseDate(dateStr); }
                catch (Exception e) { errors.add(e.getMessage()); }

                // Infer source when blank
                String source;
                if (sourceRaw.isBlank()) {
                    source = agentName.isBlank() ? "WALK_IN" : "AGENT";
                } else if (VALID_SOURCES.contains(sourceRaw)) {
                    source = sourceRaw;
                } else {
                    errors.add("Invalid source: '" + sourceRaw + "' — valid: " + VALID_SOURCES);
                    source = sourceRaw;
                }

                Long agentId = null;
                if ("AGENT".equals(source)) {
                    if (agentName.isBlank()) {
                        errors.add("Agent name is required when source is AGENT");
                    } else {
                        agentId = activeAgentsByName.get(agentName.toLowerCase());
                        if (agentId == null)
                            errors.add("Agent '" + agentName + "' not found in registry or is INACTIVE");
                    }
                }

                if (!VALID_PAYMENT_METHODS.contains(paymentMeth))
                    errors.add("Invalid payment method: '" + paymentMeth + "' — valid: " + VALID_PAYMENT_METHODS);

                // Platform: required for ECOMMERCE, validated if filled, ignored otherwise
                if ("ECOMMERCE".equals(source)) {
                    if (platformRaw.isBlank())
                        errors.add("Platform is required for ECOMMERCE source (SHOPEE, TIKTOK, LAZADA)");
                    else if (!VALID_PLATFORMS.contains(platformRaw))
                        errors.add("Invalid platform: '" + platformRaw + "' — valid: SHOPEE, TIKTOK, LAZADA");
                }

                if (!errors.isEmpty()) {
                    Map<String, Object> fix = new LinkedHashMap<>();
                    fix.put("rowIndex",   rowIdx);
                    fix.put("receiptNum", receiptNum);
                    fix.put("errors",     errors);
                    needsFix.add(fix);
                    invalidReceipts.add(receiptNum);
                    continue;
                }

                // Duplicate check against existing orders
                if (orderRepository.existsByImportRef(receiptNum)
                        || orderRepository.existsById(receiptNum)) {
                    Map<String, Object> dup = new LinkedHashMap<>();
                    dup.put("receiptNum",   receiptNum);
                    dup.put("conflictType", "HARD");
                    duplicates.add(dup);
                    duplicateReceipts.add(receiptNum);
                    continue;
                }

                salesMap.put(receiptNum,
                        new SaleAccumulator(receiptNum, date, time, customer,
                                source, agentName, agentId, paymentMeth, platformRaw, paymentStatus, new ArrayList<>()));
            }

            // Skip item parsing for receipts that are invalid or duplicates
            if (invalidReceipts.contains(receiptNum) || duplicateReceipts.contains(receiptNum)) continue;

            // ── Parse item-level columns ──────────────────────────────────────
            String itemCode    = getCol(cols, 8, "");
            String qtyStr      = getCol(cols, 9, "");
            String priceStr    = getCol(cols, 10, "");
            String basePriceItem = getCol(cols, 11, "");
            String opStr       = getCol(cols, 12, "");

            List<String> itemErrors = new ArrayList<>();

            Long productId    = null;
            String productName = null;
            if (itemCode.isBlank()) {
                itemErrors.add("Product Code is required");
            } else {
                Optional<Product> found = productRepository.findByProductCode(itemCode);
                if (found.isPresent()) { productId = found.get().getId(); productName = found.get().getName(); }
            }

            int qty = 0;
            try {
                qty = Integer.parseInt(qtyStr);
                if (qty <= 0) itemErrors.add("Qty must be positive");
            } catch (Exception e) { itemErrors.add("Invalid qty: '" + qtyStr + "'"); }

            BigDecimal unitPrice = null;
            try {
                unitPrice = new BigDecimal(priceStr);
                if (unitPrice.compareTo(BigDecimal.ZERO) <= 0) itemErrors.add("Unit Price must be positive");
            } catch (Exception e) { itemErrors.add("Invalid unit price: '" + priceStr + "'"); }

            BigDecimal basePriceVal = null;
            if (!basePriceItem.isBlank()) {
                try { basePriceVal = new BigDecimal(basePriceItem); } catch (Exception ignored) {}
            }

            BigDecimal opPerUnit = null;
            if (!opStr.isBlank()) {
                try { opPerUnit = new BigDecimal(opStr); } catch (Exception ignored) {}
            }

            if (!itemErrors.isEmpty()) {
                Map<String, Object> fix = new LinkedHashMap<>();
                fix.put("rowIndex",   rowIdx);
                fix.put("receiptNum", receiptNum);
                fix.put("errors",     itemErrors);
                needsFix.add(fix);
            } else {
                salesMap.get(receiptNum).items().add(
                        new ParsedItemRow(itemCode, productId, productName, qty, unitPrice, basePriceVal, opPerUnit));
            }
        }

        // ── Finalize: build valid list; orders with no items are errors ────────
        for (SaleAccumulator acc : salesMap.values()) {
            if (acc.items().isEmpty()) {
                Map<String, Object> fix = new LinkedHashMap<>();
                fix.put("receiptNum", acc.receiptNum());
                fix.put("errors", List.of("No valid items found for this receipt#"));
                needsFix.add(fix);
                continue;
            }

            BigDecimal computedTotal = acc.items().stream()
                    .map(i -> i.unitPrice().multiply(BigDecimal.valueOf(i.qty())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            ParsedSaleRow row = new ParsedSaleRow(
                    acc.receiptNum(), acc.date(), acc.customer(), acc.source(),
                    acc.agentName(), acc.agentId(), acc.paymentMethod(), computedTotal,
                    acc.platform(), acc.paymentStatus(),
                    Collections.unmodifiableList(acc.items()));
            validSales.add(row);

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("receiptNum",        row.receiptNum());
            m.put("date",              row.date().toString());
            m.put("customer",          row.customer());
            m.put("source",            row.source());
            m.put("agent",             row.agentName());
            m.put("paymentMethod",     row.paymentMethod());
            m.put("paymentStatus",     row.paymentStatus());
            m.put("ecommercePlatform", row.ecommercePlatform());
            m.put("itemCount",         row.items().size());
            m.put("computedTotal",     computedTotal);
            List<Map<String, Object>> itemsResponse = row.items().stream().map(item -> {
                Map<String, Object> im = new LinkedHashMap<>();
                im.put("itemCode", item.itemCode());
                im.put("productId", item.productId());
                im.put("productName", item.productName());
                im.put("matchConfidence", item.productId() != null ? "EXACT" : null);
                im.put("qty", item.qty());
                im.put("unitPrice", item.unitPrice());
                im.put("basePrice", item.basePrice());
                im.put("opPerUnit", item.opPerUnit());
                return im;
            }).collect(Collectors.toList());
            m.put("items", itemsResponse);
            valid.add(m);
        }

        String sessionToken = UUID.randomUUID().toString();
        SESSIONS.put(sessionToken, new ImportSession(
                Collections.unmodifiableList(validSales),
                Collections.emptyList(),
                System.currentTimeMillis() + SESSION_TTL_MS));
        SESSIONS.entrySet().removeIf(e -> e.getValue().expiresMs() < System.currentTimeMillis());

        return ResponseEntity.ok(Map.of(
                "sessionToken", sessionToken,
                "valid",        valid,
                "needsFix",     needsFix,
                "duplicates",   duplicates,
                "summary", Map.of(
                        "validCount",     valid.size(),
                        "needsFixCount",  needsFix.size(),
                        "duplicateCount", duplicates.size()
                )
        ));
    }

    // ── POST /api/import/upload/expenses ──────────────────────────────────────

    /**
     * Parse and validate an expenses CSV.
     * Column layout (0-based): 0=Date 1=Category 2=Sub-Category 3=Amount
     *                          4=Notes 5=Payment Method 6=Reference
     *
     * Returns: { sessionToken, valid[], needsFix[], duplicates[], summary }
     * Does NOT write to the database.
     */
    @PostMapping("/upload/expenses")
    public ResponseEntity<?> uploadExpenses(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam("file") MultipartFile file,
            @RequestParam("adminSecurityKey") String adminSecurityKey) {

        User user = resolveUploadUser(authHeader);
        if (user == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        ResponseEntity<?> roleErr = checkRole(user);
        if (roleErr != null) return roleErr;

        ResponseEntity<?> keyErr = checkKey(user, adminSecurityKey);
        if (keyErr != null) return keyErr;

        String content;
        try {
            content = new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Failed to read uploaded file"));
        }

        List<Map<String, Object>> valid        = new ArrayList<>();
        List<Map<String, Object>> needsFix     = new ArrayList<>();
        List<Map<String, Object>> duplicates   = new ArrayList<>();
        List<ParsedExpenseRow>    validExpenses = new ArrayList<>();

        String[] lines = content.split("\\r?\\n");
        boolean headerSkipped = false;
        int rowIdx = 0;

        for (String rawLine : lines) {
            String trimmed = rawLine.trim();
            if (trimmed.isEmpty()) continue;
            if (!headerSkipped) { headerSkipped = true; continue; }

            rowIdx++;
            String[] cols      = parseCsvLine(trimmed);
            List<String> errors = new ArrayList<>();

            String dateStr     = getCol(cols, 0, "");
            String catCode     = getCol(cols, 1, "").toUpperCase();
            String subCatCode  = getCol(cols, 2, "").toUpperCase();
            String amountStr   = getCol(cols, 3, "");
            String notes       = getCol(cols, 4, "");
            String paymentMeth = getCol(cols, 5, "").toUpperCase();
            String refNumber   = getCol(cols, 6, "");

            LocalDate date = null;
            try { date = parseDate(dateStr); }
            catch (Exception e) { errors.add(e.getMessage()); }

            Long    categoryId    = null;
            Long    subCategoryId = null;
            boolean catInferred   = false;
            String  catDisplay    = catCode;

            if (catCode.isBlank()) {
                errors.add("Category is required");
            } else {
                Optional<ExpenseCategory> cat = categoryRepository.findByCode(catCode);
                if (cat.isPresent() && cat.get().isActive()) {
                    // Exact code match
                    categoryId = cat.get().getId();
                } else {
                    // Exact match failed — try keyword fuzzy match
                    String[] fuzzy = fuzzyMatchCategory(catCode);
                    if (fuzzy != null) {
                        Optional<ExpenseCategory> fcat = categoryRepository.findByCode(fuzzy[0]);
                        if (fcat.isPresent() && fcat.get().isActive()) {
                            categoryId  = fcat.get().getId();
                            catInferred = true;
                            // Resolve the matched sub-category by name
                            final Long fParentId = categoryId;
                            List<ExpenseCategory> fSubs =
                                    categoryRepository.findByParentIdOrderBySortOrderAscNameAsc(fParentId);
                            Optional<ExpenseCategory> subMatch = fSubs.stream()
                                    .filter(s -> fuzzy[1].equalsIgnoreCase(s.getName()))
                                    .findFirst();
                            if (subMatch.isPresent()) {
                                subCategoryId = subMatch.get().getId();
                                catDisplay = fuzzy[0] + " › " + fuzzy[1]; // "CODE › SubName"
                            } else {
                                catDisplay = fuzzy[0];
                            }
                        } else {
                            errors.add("Category '" + catCode + "' not found or inactive");
                        }
                    } else {
                        errors.add("Category '" + catCode + "' not found or inactive");
                    }
                }
            }

            // Explicit sub-category lookup only when category was NOT inferred
            if (!catInferred && !subCatCode.isBlank() && categoryId != null) {
                final Long parentId = categoryId;
                List<ExpenseCategory> subs =
                        categoryRepository.findByParentIdOrderBySortOrderAscNameAsc(parentId);
                subCategoryId = subs.stream()
                        .filter(s -> subCatCode.equalsIgnoreCase(s.getName()))
                        .findFirst()
                        .map(ExpenseCategory::getId)
                        .orElse(null);
            }

            BigDecimal amount = null;
            try { amount = new BigDecimal(amountStr); }
            catch (Exception e) { errors.add("Invalid amount: '" + amountStr + "'"); }

            if (!VALID_PAYMENT_METHODS.contains(paymentMeth))
                errors.add("Invalid payment method: '" + paymentMeth + "' — valid: " + VALID_PAYMENT_METHODS);

            if (!errors.isEmpty()) {
                Map<String, Object> fix = new LinkedHashMap<>();
                fix.put("rowIndex", rowIdx);
                fix.put("errors",   errors);
                needsFix.add(fix);
                continue;
            }

            String finalRef = refNumber.isBlank() ? null : refNumber;

            boolean softDup = finalRef != null
                    && expenseRepository.existsByDateAndTotalAmountAndReferenceNumber(
                            date, amount, finalRef);

            ParsedExpenseRow expRow = new ParsedExpenseRow(
                    date, categoryId, subCategoryId, amount,
                    notes.isBlank() ? null : notes, paymentMeth, finalRef);

            if (softDup) {
                Map<String, Object> dup = new LinkedHashMap<>();
                dup.put("date",            date.toString());
                dup.put("amount",          amount);
                dup.put("referenceNumber", finalRef);
                dup.put("conflictType",    "SOFT");
                duplicates.add(dup);
            } else {
                validExpenses.add(expRow);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("date",            date.toString());
                m.put("amount",          amount);
                m.put("paymentMethod",   paymentMeth);
                m.put("category",        catDisplay);
                m.put("inferred",        catInferred);
                m.put("matchConfidence", catInferred ? "FUZZY" : "EXACT");
                m.put("categoryId",      categoryId);
                m.put("notes",           notes);
                m.put("referenceNumber", finalRef);
                valid.add(m);
            }
        }

        String sessionToken = UUID.randomUUID().toString();
        SESSIONS.put(sessionToken, new ImportSession(
                Collections.emptyList(),
                Collections.unmodifiableList(validExpenses),
                System.currentTimeMillis() + SESSION_TTL_MS));
        SESSIONS.entrySet().removeIf(e -> e.getValue().expiresMs() < System.currentTimeMillis());

        return ResponseEntity.ok(Map.of(
                "sessionToken", sessionToken,
                "valid",        valid,
                "needsFix",     needsFix,
                "duplicates",   duplicates,
                "summary", Map.of(
                        "validCount",     valid.size(),
                        "needsFixCount",  needsFix.size(),
                        "duplicateCount", duplicates.size()
                )
        ));
    }

    // ── POST /api/import/validate ─────────────────────────────────────────────

    /**
     * Pre-commit validation: checks stock availability and report-close status
     * for all parsed rows in the session, without writing anything.
     */
    @PostMapping("/validate")
    public ResponseEntity<?> validateImport(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, Object> body) {

        User user = resolveUploadUser(authHeader);
        if (user == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        ResponseEntity<?> roleErr = checkRole(user);
        if (roleErr != null) return roleErr;

        String sessionToken = body.getOrDefault("sessionToken", "").toString();
        ImportSession session = SESSIONS.get(sessionToken);
        if (session == null || session.expiresMs() < System.currentTimeMillis()) {
            SESSIONS.remove(sessionToken);
            return ResponseEntity.badRequest().body(Map.of("error", "Session not found or expired"));
        }

        List<Map<String, Object>> stockIssues     = new ArrayList<>();
        List<String>              reportClosedDates = new ArrayList<>();

        for (ParsedSaleRow saleRow : session.validSales()) {
            LocalDate date = saleRow.date();

            // ── Report-close check ───────────────────────────────────────────
            if (date != null && dailyReportRepository.findByReportDate(date).isPresent()) {
                if (!reportClosedDates.contains(date.toString()))
                    reportClosedDates.add(date.toString());
            }

            // ── Stock check for each item ────────────────────────────────────
            for (ParsedItemRow item : saleRow.items()) {
                Long productId = item.productId();
                if (productId == null) continue;

                Product product = productRepository.findById(productId).orElse(null);
                if (product == null) continue;

                int requested = item.qty();

                if (Boolean.TRUE.equals(product.getIsSet())) {
                    List<ProductSetComponent> comps = productSetComponentRepository.findBySetProductId(productId);
                    if (comps.isEmpty()) {
                        // SET with no components — check total stock
                        int totalAvail = product.getTotalStock();
                        if (totalAvail < requested) {
                            stockIssues.add(Map.of(
                                    "receiptNum",   saleRow.receiptNum(),
                                    "productCode",  item.itemCode(),
                                    "productName",  product.getName(),
                                    "requested",    requested,
                                    "available",    totalAvail,
                                    "shortBy",      requested - totalAvail,
                                    "warehouse",    "—",
                                    "message", "SET product '" + product.getName()
                                            + "' has no registered components — total stock: " + totalAvail
                                            + ", need " + requested));
                        }
                    } else {
                        String bestWh = inventoryService.findBestWarehouseForSet(comps, requested);
                        boolean allMet = true;
                        int shortBy = 0;
                        for (ProductSetComponent comp : comps) {
                            Product compProduct = productRepository.findById(comp.getComponentProductId()).orElse(null);
                            if (compProduct == null) { allMet = false; break; }
                            int available = inventoryService.getWhStock(compProduct, bestWh);
                            int needed = requested * comp.getQuantityPerSet();
                            if (available < needed) {
                                allMet = false;
                                shortBy += needed - available;
                            }
                        }
                        if (!allMet && shortBy > 0) {
                            stockIssues.add(Map.of(
                                    "receiptNum",   saleRow.receiptNum(),
                                    "productCode",  item.itemCode(),
                                    "productName",  product.getName(),
                                    "requested",    requested,
                                    "available",    "—",
                                    "shortBy",      shortBy,
                                    "warehouse",    bestWh.toUpperCase(),
                                    "message", "SET product '" + product.getName()
                                            + "' — components short by " + shortBy
                                            + " in " + bestWh.toUpperCase()));
                        }
                    }
                } else {
                    String bestWh = inventoryService.findBestWarehouse(product, requested);
                    int available = inventoryService.getWhStock(product, bestWh);
                    if (available < requested) {
                        int maxWh = 0;
                        for (String wh : new String[]{"wh1", "wh2", "wh3"}) {
                            maxWh = Math.max(maxWh, inventoryService.getWhStock(product, wh));
                        }
                        stockIssues.add(Map.of(
                                "receiptNum",   saleRow.receiptNum(),
                                "productCode",  item.itemCode(),
                                "productName",  product.getName(),
                                "requested",    requested,
                                "available",    maxWh,
                                "shortBy",      requested - maxWh,
                                "warehouse",    bestWh.toUpperCase(),
                                "message", "Product '" + product.getName()
                                        + "' — need " + requested + ", only " + maxWh
                                        + " available in best warehouse (" + bestWh.toUpperCase() + ")"));
                    }
                }
            }
        }

        boolean allClear = stockIssues.isEmpty();

        return ResponseEntity.ok(Map.of(
                "stockIssues",       stockIssues,
                "reportClosedDates", reportClosedDates,
                "allClear",          allClear));
    }

    // ── POST /api/import/commit ───────────────────────────────────────────────

    /**
     * Commit the validated rows from a prior /upload/sales or /upload/expenses session.
     * Body: { "sessionToken": "...", "adminSecurityKey": "...",
     *         "conflictResolutions": [{"receiptNum":"...","action":"SKIP|UPDATE|REVIEW"}] }
     */
    @PostMapping("/commit")
    public ResponseEntity<?> commitImport(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, Object> body) {

        User user = resolveUploadUser(authHeader);
        if (user == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        ResponseEntity<?> roleErr = checkRole(user);
        if (roleErr != null) return roleErr;

        String rawKey = body.getOrDefault("adminSecurityKey", "").toString();
        if (!rawKey.isBlank()) {
            ResponseEntity<?> keyErr = checkKey(user, rawKey);
            if (keyErr != null) return keyErr;
        }

        String sessionToken = body.getOrDefault("sessionToken", "").toString();
        ImportSession session = SESSIONS.get(sessionToken);
        if (session == null || session.expiresMs() < System.currentTimeMillis()) {
            SESSIONS.remove(sessionToken);
            return ResponseEntity.badRequest().body(Map.of("error", "Session not found or expired"));
        }

        // Parse conflict resolutions (SKIP overrides)
        Set<String> skipReceipts = new HashSet<>();
        Object rawRes = body.get("conflictResolutions");
        if (rawRes instanceof List<?> resList) {
            for (Object res : resList) {
                if (res instanceof Map<?, ?> m) {
                    if ("SKIP".equals(m.get("action"))) {
                        Object r = m.get("receiptNum");
                        if (r != null && !r.toString().isBlank())
                            skipReceipts.add(r.toString());
                    }
                }
            }
        }

        // Parse review overrides (user-verified values from review modal)
        Map<String, Map<String, Object>> orderOverrides = new HashMap<>();
        Map<String, Map<String, Object>> expenseOverrides = new HashMap<>();
        Object rawOverrides = body.get("overrides");
        if (rawOverrides instanceof Map<?, ?> ov) {
            Object rawOrders = ov.get("orders");
            if (rawOrders instanceof List<?> orderList) {
                for (Object o : orderList) {
                    if (o instanceof Map<?, ?> om) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> orderOverride = (Map<String, Object>) om;
                        Object rn = orderOverride.get("receiptNum");
                        if (rn != null && !rn.toString().isBlank())
                            orderOverrides.put(rn.toString(), orderOverride);
                    }
                }
            }
            Object rawExpenses = ov.get("expenses");
            if (rawExpenses instanceof List<?> expList) {
                for (Object e : expList) {
                    if (e instanceof Map<?, ?> em) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> expenseOverride = (Map<String, Object>) em;
                        Object ref = expenseOverride.get("referenceNumber");
                        if (ref != null && !ref.toString().isBlank())
                            expenseOverrides.put(ref.toString(), expenseOverride);
                    }
                }
            }
        }

        int committed = 0;
        int skipped   = 0;
        List<Map<String, Object>> errors              = new ArrayList<>();
        List<Map<String, Object>> committedOrders     = new ArrayList<>();
        List<Map<String, Object>> committedExpenses   = new ArrayList<>();
        List<String>              skippedReceipts     = new ArrayList<>();
        List<String>              skippedExpenseRefs  = new ArrayList<>();
        Long userId = user.getId();

        // ── Commit Sales → Orders (backdated to row date) ─────────────────────
        for (ParsedSaleRow saleRow : session.validSales()) {
            Map<String, Object> override = orderOverrides.get(saleRow.receiptNum());

            // Check include flag (default: included)
            if (override != null && Boolean.FALSE.equals(override.get("include"))) { skipped++; skippedReceipts.add(saleRow.receiptNum()); continue; }

            if (skipReceipts.contains(saleRow.receiptNum())) { skipped++; skippedReceipts.add(saleRow.receiptNum()); continue; }
            try {
                LocalDate targetDate  = saleRow.date();
                boolean   reportClosed = dailyReportRepository.findByReportDate(targetDate).isPresent();

                Order order = buildOrderFromRow(saleRow, override);
                order.setImported(true);
                order.setImportRef(saleRow.receiptNum());

                if (reportClosed) {
                    order.setLateImported(true);
                    String note = "Late date import — imported on " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd 'at' HH:mm")) + " by " + user.getFullName();
                    order.setNotes(note);
                }

                // createOrderAtDate: uses targetDate as the order-ID prefix and createdAt
                Order savedOrder = orderService.createOrderAtDate(order, userId, targetDate);

                // ── Payment-status routing (applies to ALL payment modes) ────
                String ps = (override != null && override.containsKey("paymentStatus"))
                        ? override.get("paymentStatus").toString()
                        : saleRow.paymentStatus();
                if ("PAID".equals(ps)) {
                    savedOrder.setPaymentStatus("PAID");
                    savedOrder.setStatus("ACTIVE");
                    orderRepository.save(savedOrder);
                    // commission created below
                } else if ("UNPAID".equals(ps)) {
                    savedOrder.setPaymentStatus("UNPAID");
                    savedOrder.setStatus("PENDING_COLLECTION");
                    savedOrder.setPendingCollectionAt(OffsetDateTime.now());
                    orderRepository.save(savedOrder);
                    try {
                        transactionService.recordDeferralVoid(savedOrder, userId);
                    } catch (Exception ignored) {}
                    // Skip commission for unpaid orders
                    committed++;
                    Map<String, Object> summary = new LinkedHashMap<>();
                    summary.put("receiptNum",    saleRow.receiptNum());
                    summary.put("orderId",       savedOrder.getId());
                    summary.put("date",          targetDate.toString());
                    summary.put("customer",      saleRow.customer());
                    summary.put("total",         savedOrder.getTotal());
                    summary.put("lateImported",  reportClosed);
                    committedOrders.add(summary);
                    continue; // skip commission, skip regular summary
                }
                // If blank/null → leave as ACTIVE (default), commission created below

                try { commissionService.createEntriesForOrder(savedOrder, userId); }
                catch (Exception e) {
                    log.warn("Failed to create commission entries for imported order {}: {}",
                             savedOrder.getId(), e.getMessage());
                }

                committed++;

                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("receiptNum",    saleRow.receiptNum());
                summary.put("orderId",       savedOrder.getId());
                summary.put("date",          targetDate.toString());
                summary.put("customer",      saleRow.customer());
                summary.put("total",         savedOrder.getTotal());
                summary.put("lateImported",  reportClosed);
                committedOrders.add(summary);

            } catch (Exception e) {
                errors.add(Map.of(
                        "section",    "Sales",
                        "receiptNum", saleRow.receiptNum(),
                        "error",      e.getMessage() != null ? e.getMessage() : "Unknown error"));
            }
        }

        // ── Log batch import of sales orders (non-late only) ─────────────────
        if (!committedOrders.isEmpty()) {
            long nonLateCount = committedOrders.stream()
                    .filter(o -> !Boolean.TRUE.equals(o.get("lateImported")))
                    .count();
            if (nonLateCount > 0) {
                BigDecimal salesTotal = committedOrders.stream()
                        .filter(o -> !Boolean.TRUE.equals(o.get("lateImported")))
                        .map(o -> (BigDecimal) o.get("total"))
                        .filter(Objects::nonNull)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                activityLogService.log(userId, user.getFullName(), "BATCH_IMPORT_SALES",
                        "Imported " + nonLateCount + " sales order(s) via batch — Total: ₱" + salesTotal,
                        "IMPORT", sessionToken);
            }
        }

        // ── Commit Expenses ───────────────────────────────────────────────────
        for (ParsedExpenseRow expRow : session.validExpenses()) {
            Map<String, Object> override = expenseOverrides.get(expRow.referenceNumber());

            if (override != null && Boolean.FALSE.equals(override.get("include"))) { skipped++; skippedExpenseRefs.add(expRow.referenceNumber()); continue; }

            try {
                LocalDate expDate = expRow.date();
                boolean reportClosed = dailyReportRepository.findByReportDate(expDate).isPresent();

                Expense expense = buildExpenseFromRow(expRow, user, override);

                // Build summary values (use overridden amount if present)
                BigDecimal finalAmount = (override != null && override.containsKey("amount"))
                        ? new BigDecimal(override.get("amount").toString())
                        : expRow.amount();

                if (reportClosed) {
                    expense.setLateImported(true);
                    String note = "Late date import — imported on " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd 'at' HH:mm")) + " by " + user.getFullName();
                    String existing = expense.getNotes();
                    expense.setNotes(existing != null ? existing + " | " + note : note);
                } else {
                    activityLogService.log(userId, user.getFullName(), "EXPENSE_RECORDED",
                            "Imported expense of ₱" + finalAmount + " on " + expDate,
                            "EXPENSE", null);
                }

                expenseRepository.save(expense);
                committed++;

                Map<String, Object> expSummary = new LinkedHashMap<>();
                expSummary.put("date",          expDate.toString());
                expSummary.put("amount",        finalAmount);
                expSummary.put("lateImported",  reportClosed);
                committedExpenses.add(expSummary);

            } catch (Exception e) {
                errors.add(Map.of(
                        "section", "Expenses",
                        "date",    expRow.date().toString(),
                        "error",   e.getMessage() != null ? e.getMessage() : "Unknown error"));
            }
        }

        SESSIONS.remove(sessionToken);

        // ── Persist commit log ────────────────────────────────────────────────
        Map<String, Object> resultSnapshot = new LinkedHashMap<>();
        resultSnapshot.put("committedOrders", committedOrders);
        resultSnapshot.put("committedExpenses", committedExpenses);
        resultSnapshot.put("skippedOrders", skippedReceipts);
        resultSnapshot.put("skippedExpenses", skippedExpenseRefs);
        resultSnapshot.put("errors", errors);

        LocalDate batchDate = LocalDate.now();

        ImportCommitLog commitLog = new ImportCommitLog();
        commitLog.setImportRef(sessionToken);
        commitLog.setCommittedById(userId);
        commitLog.setCommittedAt(LocalDateTime.now());
        commitLog.setBatchDate(batchDate);

        try {
            commitLog.setResultJson(new ObjectMapper().writeValueAsString(resultSnapshot));
        } catch (Exception e) {
            commitLog.setResultJson("{}");
        }

        importCommitLogRepository.save(commitLog);

        return ResponseEntity.ok(Map.of(
                "logId",             commitLog.getId(),
                "committed",         committed,
                "skipped",           skipped,
                "errors",            errors,
                "committedOrders",   committedOrders,
                "committedExpenses", committedExpenses));
    }

    // ── POST /api/import/upload/combined ──────────────────────────────────────

    /**
     * Parse and validate both sales CSV + expenses CSV from a single request.
     * Body (multipart): salesFile + expensesFile + adminSecurityKey
     *
     * Returns the same shape as /upload/sales — { sessionToken, valid[], needsFix[], duplicates[], summary }
     * with the valid array containing both order-level and expense-level entries (distinguished by "type": "order"
     * or "type": "expense").
     */
    @PostMapping("/upload/combined")
    public ResponseEntity<?> uploadCombined(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam("salesFile")    MultipartFile salesFile,
            @RequestParam("expensesFile") MultipartFile expensesFile,
            @RequestParam("adminSecurityKey") String adminSecurityKey) {

        User user = resolveUploadUser(authHeader);
        if (user == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        ResponseEntity<?> roleErr = checkRole(user);
        if (roleErr != null) return roleErr;

        ResponseEntity<?> keyErr = checkKey(user, adminSecurityKey);
        if (keyErr != null) return keyErr;

        List<Map<String, Object>> valid       = new ArrayList<>();
        List<Map<String, Object>> needsFix    = new ArrayList<>();
        List<Map<String, Object>> duplicates  = new ArrayList<>();
        List<ParsedSaleRow>       validSales  = new ArrayList<>();
        List<ParsedExpenseRow>    validExpenses = new ArrayList<>();

        // ── Parse sales file ─────────────────────────────────────────────────
        try {
            String content = new String(salesFile.getBytes(), StandardCharsets.UTF_8);
            Map<String, Object> salesResult = parseSalesCsv(content, user);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> sValid = (List<Map<String, Object>>) salesResult.get("valid");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> sFix   = (List<Map<String, Object>>) salesResult.get("needsFix");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> sDup   = (List<Map<String, Object>>) salesResult.get("duplicates");
            @SuppressWarnings("unchecked")
            List<ParsedSaleRow> sRows = (List<ParsedSaleRow>) salesResult.get("parsedRows");

            for (Map<String, Object> m : sValid) {
                m.put("type", "order");
                valid.add(m);
            }
            needsFix.addAll(sFix);
            duplicates.addAll(sDup);
            validSales.addAll(sRows);

        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "Failed to parse sales file: " + (e.getMessage() != null ? e.getMessage() : "Unknown error")));
        }

        // ── Parse expenses file ──────────────────────────────────────────────
        try {
            String content = new String(expensesFile.getBytes(), StandardCharsets.UTF_8);
            Map<String, Object> expResult = parseExpensesCsv(content, user);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> eValid = (List<Map<String, Object>>) expResult.get("valid");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> eFix   = (List<Map<String, Object>>) expResult.get("needsFix");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> eDup   = (List<Map<String, Object>>) expResult.get("duplicates");
            @SuppressWarnings("unchecked")
            List<ParsedExpenseRow> eRows = (List<ParsedExpenseRow>) expResult.get("parsedRows");

            for (Map<String, Object> m : eValid) {
                m.put("type", "expense");
                valid.add(m);
            }
            needsFix.addAll(eFix);
            duplicates.addAll(eDup);
            validExpenses.addAll(eRows);

        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "Failed to parse expenses file: " + (e.getMessage() != null ? e.getMessage() : "Unknown error")));
        }

        String sessionToken = UUID.randomUUID().toString();
        SESSIONS.put(sessionToken, new ImportSession(
                Collections.unmodifiableList(validSales),
                Collections.unmodifiableList(validExpenses),
                System.currentTimeMillis() + SESSION_TTL_MS));
        SESSIONS.entrySet().removeIf(e -> e.getValue().expiresMs() < System.currentTimeMillis());

        return ResponseEntity.ok(Map.of(
                "sessionToken", sessionToken,
                "valid",        valid,
                "needsFix",     needsFix,
                "duplicates",   duplicates,
                "summary", Map.of(
                        "validCount",     valid.size(),
                        "needsFixCount",  needsFix.size(),
                        "duplicateCount", duplicates.size()
                )
        ));
    }

    // ── POST /api/import/close ────────────────────────────────────────────────

    /**
     * Close daily reports for a set of dates that were committed via batch import.
     * Body: { "dates": ["2026-06-07", "2026-06-08"] }
     *
     * Idempotent: dates that already have a report are skipped silently.
     */
    @PostMapping("/close")
    public ResponseEntity<?> closeImportReports(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, Object> body) {

        User user = resolveUploadUser(authHeader);
        if (user == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        ResponseEntity<?> roleErr = checkRole(user);
        if (roleErr != null) return roleErr;

        String rawKey = body.getOrDefault("adminSecurityKey", "").toString();
        if (!rawKey.isBlank()) {
            ResponseEntity<?> keyErr = checkKey(user, rawKey);
            if (keyErr != null) return keyErr;
        }

        @SuppressWarnings("unchecked")
        List<String> dateStrings = (List<String>) body.getOrDefault("dates", List.of());
        if (dateStrings.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "dates list is required"));
        }

        List<Map<String, Object>> results = new ArrayList<>();
        for (String ds : dateStrings) {
            try {
                LocalDate date = parseDate(ds);
                dailyReportService.closeForImportDate(user.getId(), user.getFullName(), date);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("date",   date.toString());
                r.put("status", "created");
                results.add(r);
            } catch (Exception e) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("date",   ds);
                r.put("status", "failed");
                r.put("reason", e.getMessage() != null ? e.getMessage() : "Unknown error");
                results.add(r);
            }
        }

        return ResponseEntity.ok(Map.of("closedReports", results));
    }

    // ── GET /api/import/history/batch ─────────────────────────────────────────

    @GetMapping("/history/batch")
    public ResponseEntity<?> getImportBatchDetail(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        User user = resolveUploadUser(authHeader);
        if (user == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        ResponseEntity<?> roleErr = checkRole(user);
        if (roleErr != null) return roleErr;

        List<Order>   orders   = orderRepository.findImportedOrdersWithCreatorByDateRange(date, date);
        List<Expense> expenses = expenseRepository.findImportedExpensesByDateRange(date, date);

        List<Map<String, Object>> orderMaps = orders.stream().map(o -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("orderId",   o.getId());
            m.put("customer",  o.getCustomerName());
            m.put("source",    o.getSource());
            m.put("total",     o.getTotal());
            m.put("importRef", o.getImportRef());
            return m;
        }).collect(Collectors.toList());

        List<Map<String, Object>> expenseMaps = expenses.stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date",            e.getDate() != null ? e.getDate().toString() : null);
            m.put("totalAmount",     e.getTotalAmount());
            m.put("paymentMethod",   e.getPaymentMethod());
            m.put("referenceNumber", e.getReferenceNumber());
            m.put("importRef",       e.getImportRef());
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("date",     date.toString());
        response.put("orders",   orderMaps);
        response.put("expenses", expenseMaps);

        return ResponseEntity.ok(response);
    }

    // ── GET /api/import/history ───────────────────────────────────────────────

    @GetMapping("/history")
    public ResponseEntity<?> getImportHistory(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {

        User user = resolveUploadUser(authHeader);
        if (user == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        ResponseEntity<?> roleErr = checkRole(user);
        if (roleErr != null) return roleErr;

        LocalDate endDate   = end   != null ? end   : LocalDate.now();
        LocalDate startDate = start != null ? start : endDate.minusDays(30);

        List<Order>   importedOrders   = orderRepository.findImportedOrdersWithCreatorByDateRange(startDate, endDate);
        List<Expense> importedExpenses = expenseRepository.findImportedExpensesByDateRange(startDate, endDate);

        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();

        for (Order o : importedOrders) {
            LocalDate d    = o.getCreatedAt().toLocalDate();
            String    name = o.getCreatedBy() != null ? o.getCreatedBy().getFullName() : "Unknown";
            String    key  = d + "|" + name;
            Map<String, Object> batch = merged.computeIfAbsent(key, k -> initBatch(d, name));
            batch.put("ordersCount",     (int) batch.get("ordersCount") + 1);
            BigDecimal cur = (BigDecimal) batch.get("totalOrderValue");
            batch.put("totalOrderValue", cur.add(o.getTotal() != null ? o.getTotal() : BigDecimal.ZERO));
        }

        for (Expense e : importedExpenses) {
            LocalDate d    = e.getDate();
            String    name = e.getAdminName() != null ? e.getAdminName() : "Unknown";
            String    key  = d + "|" + name;
            Map<String, Object> batch = merged.computeIfAbsent(key, k -> initBatch(d, name));
            batch.put("expensesCount",      (int) batch.get("expensesCount") + 1);
            BigDecimal cur = (BigDecimal) batch.get("totalExpenseAmount");
            batch.put("totalExpenseAmount", cur.add(e.getTotalAmount() != null ? e.getTotalAmount() : BigDecimal.ZERO));
        }

        List<Map<String, Object>> result = new ArrayList<>(merged.values());
        result.sort(Comparator.comparing(m -> (String) m.get("importDate"), Comparator.reverseOrder()));

        return ResponseEntity.ok(result);
    }

    private static Map<String, Object> initBatch(LocalDate date, String adminName) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("importDate",         date.toString());
        m.put("importedBy",         adminName);
        m.put("ordersCount",        0);
        m.put("expensesCount",      0);
        m.put("totalOrderValue",    BigDecimal.ZERO);
        m.put("totalExpenseAmount", BigDecimal.ZERO);
        return m;
    }

    // ── GET /api/import/history/{importRef} ───────────────────────────────────

    @GetMapping("/history/{importRef}")
    public ResponseEntity<?> getImportDetail(
            @PathVariable String importRef,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        User user = resolveUploadUser(authHeader);
        if (user == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        ResponseEntity<?> roleErr = checkRole(user);
        if (roleErr != null) return roleErr;

        Optional<Order> optOrder = orderRepository.findByImportRefWithItems(importRef);
        if (optOrder.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error",
                    "No imported order found with importRef: " + importRef));
        }

        Order order = optOrder.get();
        List<Map<String, Object>> itemMaps = order.getItems().stream().map(item -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("productName", item.getProductName());
            m.put("quantity",    item.getQuantity());
            m.put("unitPrice",   item.getUnitPrice());
            m.put("subtotal",    item.getSubtotal());
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("importRef",  order.getImportRef());
        response.put("orderId",    order.getId());
        response.put("orderDate",  order.getCreatedAt() != null
                ? order.getCreatedAt().toLocalDate().toString() : null);
        response.put("customer",   order.getCustomerName());
        response.put("total",      order.getTotal());
        response.put("isImported", order.isImported());
        response.put("items",      itemMaps);

        return ResponseEntity.ok(response);
    }

    // ── GET /api/import/history/logs ───────────────────────────────────────────

    @GetMapping("/history/logs")
    public ResponseEntity<?> getCommitLogList(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        User user = resolveUploadUser(authHeader);
        if (user == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        ResponseEntity<?> roleErr = checkRole(user);
        if (roleErr != null) return roleErr;

        List<Map<String, Object>> result = new ArrayList<>();
        for (ImportCommitLog log : importCommitLogRepository.findAllByOrderByCommittedAtDesc()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", log.getId());
            entry.put("committedAt", log.getCommittedAt().toString());
            entry.put("batchDate", log.getBatchDate().toString());
            entry.put("committedBy", log.getCommittedById());

            // Extract lightweight counts from stored JSON
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> json = new ObjectMapper().readValue(log.getResultJson(), Map.class);
                entry.put("ordersCount", ((List<?>) json.getOrDefault("committedOrders", List.of())).size());
                entry.put("expensesCount", ((List<?>) json.getOrDefault("committedExpenses", List.of())).size());
                entry.put("skippedCount", ((List<?>) json.getOrDefault("skippedOrders", List.of())).size()
                        + ((List<?>) json.getOrDefault("skippedExpenses", List.of())).size());
            } catch (Exception e) {
                entry.put("ordersCount", 0);
                entry.put("expensesCount", 0);
                entry.put("skippedCount", 0);
            }

            result.add(entry);
        }

        return ResponseEntity.ok(result);
    }

    // ── GET /api/import/history/logs/{id} ─────────────────────────────────────

    @GetMapping("/history/logs/{id}")
    public ResponseEntity<?> getCommitLogDetail(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        User user = resolveUploadUser(authHeader);
        if (user == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        ResponseEntity<?> roleErr = checkRole(user);
        if (roleErr != null) return roleErr;

        ImportCommitLog log = importCommitLogRepository.findById(id).orElse(null);
        if (log == null) {
            return ResponseEntity.status(404).body(Map.of("error", "Commit log not found"));
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", log.getId());
        response.put("importRef", log.getImportRef());
        response.put("committedAt", log.getCommittedAt().toString());
        response.put("batchDate", log.getBatchDate().toString());
        response.put("committedBy", log.getCommittedById());

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = new ObjectMapper().readValue(log.getResultJson(), Map.class);
            response.put("result", parsed);
        } catch (Exception e) {
            response.put("result", Map.of());
        }

        return ResponseEntity.ok(response);
    }

    // ── Shared CSV parsers (used by /upload/sales and /upload/combined) ─────

    /**
     * Parse a sales CSV string into validated ParsedSaleRow records and preview maps.
     * Does NOT create a session — returns a Map with keys:
     *   "valid"       — List<Map<>> preview entries
     *   "needsFix"    — List<Map<>> error entries
     *   "duplicates"  — List<Map<>> duplicate entries
     *   "parsedRows"  — List<ParsedSaleRow> validated sale rows
     */
    private Map<String, Object> parseSalesCsv(String content, User user) {
        Map<String, Long> activeAgentsByName = agentRepository
                .findByStatusOrderByFullNameAsc("ACTIVE").stream()
                .collect(Collectors.toMap(
                        a -> a.getFullName().toLowerCase(),
                        Agent::getId,
                        (a, b) -> a));

        List<Map<String, Object>> valid       = new ArrayList<>();
        List<Map<String, Object>> needsFix    = new ArrayList<>();
        List<Map<String, Object>> duplicates  = new ArrayList<>();
        List<ParsedSaleRow>       validSales  = new ArrayList<>();

        Map<String, SaleAccumulator> salesMap         = new LinkedHashMap<>();
        Set<String>                  invalidReceipts  = new HashSet<>();
        Set<String>                  duplicateReceipts = new HashSet<>();

        String[] lines = content.split("\\r?\\n");
        boolean headerSkipped = false;
        int rowIdx = 0;

        for (String rawLine : lines) {
            String trimmed = rawLine.trim();
            if (trimmed.isEmpty()) continue;
            if (!headerSkipped) { headerSkipped = true; continue; }

            rowIdx++;
            String[] cols     = parseCsvLine(trimmed);
            String receiptNum = getCol(cols, 1, "");

            if (receiptNum.isBlank()) {
                Map<String, Object> fix = new LinkedHashMap<>();
                fix.put("rowIndex", rowIdx);
                fix.put("receiptNum", "(blank)");
                fix.put("errors", List.of("Receipt# is required"));
                needsFix.add(fix);
                continue;
            }

            boolean alreadyTracked = salesMap.containsKey(receiptNum)
                    || invalidReceipts.contains(receiptNum)
                    || duplicateReceipts.contains(receiptNum);

            if (!alreadyTracked) {
                List<String> errors = new ArrayList<>();

                String dateStr     = getCol(cols, 0, "");
                String time        = getCol(cols, 2, "");
                String customer    = getCol(cols, 3, "");
                String sourceRaw   = getCol(cols, 4, "").toUpperCase();
                String agentName   = getCol(cols, 5, "");
                String paymentMeth = getCol(cols, 6, "").toUpperCase();
                String platformRaw = getCol(cols, 7, "").toUpperCase();
                String paymentStatus = getCol(cols, 13, "").toUpperCase();

                LocalDate date = null;
                try { date = parseDate(dateStr); }
                catch (Exception e) { errors.add(e.getMessage()); }

                String source;
                if (sourceRaw.isBlank()) {
                    source = agentName.isBlank() ? "WALK_IN" : "AGENT";
                } else if (VALID_SOURCES.contains(sourceRaw)) {
                    source = sourceRaw;
                } else {
                    errors.add("Invalid source: '" + sourceRaw + "' — valid: " + VALID_SOURCES);
                    source = sourceRaw;
                }

                Long agentId = null;
                if ("AGENT".equals(source)) {
                    if (agentName.isBlank()) {
                        errors.add("Agent name is required when source is AGENT");
                    } else {
                        agentId = activeAgentsByName.get(agentName.toLowerCase());
                        if (agentId == null)
                            errors.add("Agent '" + agentName + "' not found in registry or is INACTIVE");
                    }
                }

                if (!VALID_PAYMENT_METHODS.contains(paymentMeth))
                    errors.add("Invalid payment method: '" + paymentMeth + "' — valid: " + VALID_PAYMENT_METHODS);

                if ("ECOMMERCE".equals(source)) {
                    if (platformRaw.isBlank())
                        errors.add("Platform is required for ECOMMERCE source (SHOPEE, TIKTOK, LAZADA)");
                    else if (!VALID_PLATFORMS.contains(platformRaw))
                        errors.add("Invalid platform: '" + platformRaw + "' — valid: SHOPEE, TIKTOK, LAZADA");
                }

                if (!errors.isEmpty()) {
                    Map<String, Object> fix = new LinkedHashMap<>();
                    fix.put("rowIndex",   rowIdx);
                    fix.put("receiptNum", receiptNum);
                    fix.put("errors",     errors);
                    needsFix.add(fix);
                    invalidReceipts.add(receiptNum);
                    continue;
                }

                if (orderRepository.existsByImportRef(receiptNum)
                        || orderRepository.existsById(receiptNum)) {
                    Map<String, Object> dup = new LinkedHashMap<>();
                    dup.put("receiptNum",   receiptNum);
                    dup.put("conflictType", "HARD");
                    duplicates.add(dup);
                    duplicateReceipts.add(receiptNum);
                    continue;
                }

                salesMap.put(receiptNum,
                        new SaleAccumulator(receiptNum, date, time, customer,
                                source, agentName, agentId, paymentMeth, platformRaw, paymentStatus, new ArrayList<>()));
            }

            if (invalidReceipts.contains(receiptNum) || duplicateReceipts.contains(receiptNum)) continue;

            String itemCode    = getCol(cols, 8, "");
            String qtyStr      = getCol(cols, 9, "");
            String priceStr    = getCol(cols, 10, "");
            String basePriceItem = getCol(cols, 11, "");
            String opStr       = getCol(cols, 12, "");

            List<String> itemErrors = new ArrayList<>();

            Long productId    = null;
            String productName = null;
            if (itemCode.isBlank()) {
                itemErrors.add("Product Code is required");
            } else {
                Optional<Product> found = productRepository.findByProductCode(itemCode);
                if (found.isPresent()) { productId = found.get().getId(); productName = found.get().getName(); }
            }

            int qty = 0;
            try {
                qty = Integer.parseInt(qtyStr);
                if (qty <= 0) itemErrors.add("Qty must be positive");
            } catch (Exception e) { itemErrors.add("Invalid qty: '" + qtyStr + "'"); }

            BigDecimal unitPrice = null;
            try {
                unitPrice = new BigDecimal(priceStr);
                if (unitPrice.compareTo(BigDecimal.ZERO) <= 0) itemErrors.add("Unit Price must be positive");
            } catch (Exception e) { itemErrors.add("Invalid unit price: '" + priceStr + "'"); }

            BigDecimal basePriceVal = null;
            if (!basePriceItem.isBlank()) {
                try { basePriceVal = new BigDecimal(basePriceItem); } catch (Exception ignored) {}
            }

            BigDecimal opPerUnit = null;
            if (!opStr.isBlank()) {
                try { opPerUnit = new BigDecimal(opStr); } catch (Exception ignored) {}
            }

            if (!itemErrors.isEmpty()) {
                Map<String, Object> fix = new LinkedHashMap<>();
                fix.put("rowIndex",   rowIdx);
                fix.put("receiptNum", receiptNum);
                fix.put("errors",     itemErrors);
                needsFix.add(fix);
            } else {
                salesMap.get(receiptNum).items().add(
                        new ParsedItemRow(itemCode, productId, productName, qty, unitPrice, basePriceVal, opPerUnit));
            }
        }

        for (SaleAccumulator acc : salesMap.values()) {
            if (acc.items().isEmpty()) {
                Map<String, Object> fix = new LinkedHashMap<>();
                fix.put("receiptNum", acc.receiptNum());
                fix.put("errors", List.of("No valid items found for this receipt#"));
                needsFix.add(fix);
                continue;
            }

            BigDecimal computedTotal = acc.items().stream()
                    .map(i -> i.unitPrice().multiply(BigDecimal.valueOf(i.qty())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            ParsedSaleRow row = new ParsedSaleRow(
                    acc.receiptNum(), acc.date(), acc.customer(), acc.source(),
                    acc.agentName(), acc.agentId(), acc.paymentMethod(), computedTotal,
                    acc.platform(), acc.paymentStatus(),
                    Collections.unmodifiableList(acc.items()));
            validSales.add(row);

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("receiptNum",        row.receiptNum());
            m.put("date",              row.date().toString());
            m.put("customer",          row.customer());
            m.put("source",            row.source());
            m.put("agent",             row.agentName());
            m.put("paymentMethod",     row.paymentMethod());
            m.put("paymentStatus",     row.paymentStatus());
            m.put("ecommercePlatform", row.ecommercePlatform());
            m.put("itemCount",         row.items().size());
            m.put("computedTotal",     computedTotal);
            List<Map<String, Object>> itemsResponse = row.items().stream().map(item -> {
                Map<String, Object> im = new LinkedHashMap<>();
                im.put("itemCode", item.itemCode());
                im.put("productId", item.productId());
                im.put("productName", item.productName());
                im.put("matchConfidence", item.productId() != null ? "EXACT" : null);
                im.put("qty", item.qty());
                im.put("unitPrice", item.unitPrice());
                im.put("basePrice", item.basePrice());
                im.put("opPerUnit", item.opPerUnit());
                return im;
            }).collect(Collectors.toList());
            m.put("items", itemsResponse);
            valid.add(m);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid",      valid);
        result.put("needsFix",   needsFix);
        result.put("duplicates", duplicates);
        result.put("parsedRows", validSales);
        return result;
    }

    /**
     * Parse an expenses CSV string into validated ParsedExpenseRow records and preview maps.
     * Returns the same shape as parseSalesCsv.
     */
    private Map<String, Object> parseExpensesCsv(String content, User user) {
        List<Map<String, Object>> valid        = new ArrayList<>();
        List<Map<String, Object>> needsFix     = new ArrayList<>();
        List<Map<String, Object>> duplicates   = new ArrayList<>();
        List<ParsedExpenseRow>    validExpenses = new ArrayList<>();

        String[] lines = content.split("\\r?\\n");
        boolean headerSkipped = false;
        int rowIdx = 0;

        for (String rawLine : lines) {
            String trimmed = rawLine.trim();
            if (trimmed.isEmpty()) continue;
            if (!headerSkipped) { headerSkipped = true; continue; }

            rowIdx++;
            String[] cols      = parseCsvLine(trimmed);
            List<String> errors = new ArrayList<>();

            String dateStr     = getCol(cols, 0, "");
            String catCode     = getCol(cols, 1, "").toUpperCase();
            String subCatCode  = getCol(cols, 2, "").toUpperCase();
            String amountStr   = getCol(cols, 3, "");
            String notes       = getCol(cols, 4, "");
            String paymentMeth = getCol(cols, 5, "").toUpperCase();
            String refNumber   = getCol(cols, 6, "");

            LocalDate date = null;
            try { date = parseDate(dateStr); }
            catch (Exception e) { errors.add(e.getMessage()); }

            Long    categoryId    = null;
            Long    subCategoryId = null;
            boolean catInferred   = false;
            String  catDisplay    = catCode;

            if (catCode.isBlank()) {
                errors.add("Category is required");
            } else {
                Optional<ExpenseCategory> cat = categoryRepository.findByCode(catCode);
                if (cat.isPresent() && cat.get().isActive()) {
                    categoryId = cat.get().getId();
                } else {
                    String[] fuzzy = fuzzyMatchCategory(catCode);
                    if (fuzzy != null) {
                        Optional<ExpenseCategory> fcat = categoryRepository.findByCode(fuzzy[0]);
                        if (fcat.isPresent() && fcat.get().isActive()) {
                            categoryId  = fcat.get().getId();
                            catInferred = true;
                            final Long fParentId = categoryId;
                            List<ExpenseCategory> fSubs =
                                    categoryRepository.findByParentIdOrderBySortOrderAscNameAsc(fParentId);
                            Optional<ExpenseCategory> subMatch = fSubs.stream()
                                    .filter(s -> fuzzy[1].equalsIgnoreCase(s.getName()))
                                    .findFirst();
                            if (subMatch.isPresent()) {
                                subCategoryId = subMatch.get().getId();
                                catDisplay = fuzzy[0] + " › " + fuzzy[1];
                            } else {
                                catDisplay = fuzzy[0];
                            }
                        } else {
                            errors.add("Category '" + catCode + "' not found or inactive");
                        }
                    } else {
                        errors.add("Category '" + catCode + "' not found or inactive");
                    }
                }
            }

            if (!catInferred && !subCatCode.isBlank() && categoryId != null) {
                final Long parentId = categoryId;
                List<ExpenseCategory> subs =
                        categoryRepository.findByParentIdOrderBySortOrderAscNameAsc(parentId);
                subCategoryId = subs.stream()
                        .filter(s -> subCatCode.equalsIgnoreCase(s.getName()))
                        .findFirst()
                        .map(ExpenseCategory::getId)
                        .orElse(null);
            }

            BigDecimal amount = null;
            try { amount = new BigDecimal(amountStr); }
            catch (Exception e) { errors.add("Invalid amount: '" + amountStr + "'"); }

            if (!VALID_PAYMENT_METHODS.contains(paymentMeth))
                errors.add("Invalid payment method: '" + paymentMeth + "' — valid: " + VALID_PAYMENT_METHODS);

            if (!errors.isEmpty()) {
                Map<String, Object> fix = new LinkedHashMap<>();
                fix.put("rowIndex", rowIdx);
                fix.put("errors",   errors);
                needsFix.add(fix);
                continue;
            }

            String finalRef = refNumber.isBlank() ? null : refNumber;

            boolean softDup = finalRef != null
                    && expenseRepository.existsByDateAndTotalAmountAndReferenceNumber(
                            date, amount, finalRef);

            ParsedExpenseRow expRow = new ParsedExpenseRow(
                    date, categoryId, subCategoryId, amount,
                    notes.isBlank() ? null : notes, paymentMeth, finalRef);

            if (softDup) {
                Map<String, Object> dup = new LinkedHashMap<>();
                dup.put("date",            date.toString());
                dup.put("amount",          amount);
                dup.put("referenceNumber", finalRef);
                dup.put("conflictType",    "SOFT");
                duplicates.add(dup);
            } else {
                validExpenses.add(expRow);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("date",            date.toString());
                m.put("amount",          amount);
                m.put("paymentMethod",   paymentMeth);
                m.put("category",        catDisplay);
                m.put("inferred",        catInferred);
                m.put("matchConfidence", catInferred ? "FUZZY" : "EXACT");
                m.put("categoryId",      categoryId);
                m.put("notes",           notes);
                m.put("referenceNumber", finalRef);
                valid.add(m);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("valid",      valid);
        result.put("needsFix",   needsFix);
        result.put("duplicates", duplicates);
        result.put("parsedRows", validExpenses);
        return result;
    }

    // ── Builder helpers ───────────────────────────────────────────────────────

    private Order buildOrderFromRow(ParsedSaleRow row) {
        Order order = new Order();
        order.setCustomerName(row.customer());
        order.setSource(row.source());
        order.setAgentName(row.agentName().isBlank() ? null : row.agentName());
        order.setAgentId(row.agentId());
        order.setPaymentMode(row.paymentMethod());
        order.setPaymentStatus(row.paymentStatus());
        order.setOrderType("STANDARD");
        order.setDiscount(BigDecimal.ZERO);
        order.setDeliveryFee(BigDecimal.ZERO);
        order.setNotes("Imported from Excel");
        if ("ECOMMERCE".equals(row.source())
                && row.ecommercePlatform() != null
                && !row.ecommercePlatform().isBlank()) {
            order.setEcommercePlatform(row.ecommercePlatform());
        }

        for (ParsedItemRow itemRow : row.items()) {
            OrderItem item = new OrderItem();
            item.setWarehouse(null);
            item.setProductId(itemRow.productId());
            item.setProductName(itemRow.productName());
            item.setQuantity(itemRow.qty());
            item.setUnitPrice(itemRow.unitPrice());
            // Warehouse auto-selected by InventoryService.deductStockForOrder()
            if (row.agentId() != null && itemRow.opPerUnit() != null) {
                if (itemRow.basePrice() != null) item.setBasePrice(itemRow.basePrice());
                item.setOpPerUnit(itemRow.opPerUnit());
                item.setOpAmount(
                    itemRow.opPerUnit()
                           .multiply(new BigDecimal(itemRow.qty()))
                           .setScale(2, RoundingMode.HALF_UP)
                );
            }
            order.addItem(item);
        }
        return order;
    }

    /** Overload that applies review-modal overrides on top of parsed row data. */
    private Order buildOrderFromRow(ParsedSaleRow row, Map<String, Object> override) {
        Order order = buildOrderFromRow(row);
        if (override == null) return order;

        if (override.containsKey("agentId") && override.get("agentId") != null) {
            Number agentNum = (Number) override.get("agentId");
            order.setAgentId(agentNum.longValue());
            agentRepository.findById(agentNum.longValue()).ifPresent(a ->
                    order.setAgentName(a.getFullName()));
        }
        if (override.containsKey("paymentMethod") && override.get("paymentMethod") != null) {
            order.setPaymentMode(override.get("paymentMethod").toString());
        }
        if (override.containsKey("paymentStatus")) {
            Object ps = override.get("paymentStatus");
            order.setPaymentStatus(ps != null ? ps.toString() : null);
        }
        if (override.containsKey("ecommercePlatform")) {
            Object plat = override.get("ecommercePlatform");
            order.setEcommercePlatform(plat != null && !plat.toString().isBlank() ? plat.toString() : null);
        }

        // Apply item-level overrides
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> itemOverrides = (List<Map<String, Object>>) override.get("items");
        if (itemOverrides != null) {
            Map<Integer, Map<String, Object>> idxMap = new HashMap<>();
            for (Map<String, Object> io : itemOverrides) {
                Object ri = io.get("rowIndex");
                if (ri instanceof Number n) idxMap.put(n.intValue(), io);
            }
            List<OrderItem> items = order.getItems();
            for (int i = 0; i < items.size(); i++) {
                Map<String, Object> io = idxMap.get(i);
                if (io == null) continue;
                OrderItem item = items.get(i);

                if (io.containsKey("qty")) {
                    item.setQuantity(((Number) io.get("qty")).intValue());
                }
                if (io.containsKey("unitPrice")) {
                    item.setUnitPrice(new BigDecimal(io.get("unitPrice").toString()));
                }
                if (io.containsKey("basePrice")) {
                    Object bp = io.get("basePrice");
                    item.setBasePrice(bp != null ? new BigDecimal(bp.toString()) : null);
                }
                if (io.containsKey("opPerUnit")) {
                    Object op = io.get("opPerUnit");
                    if (op != null) {
                        BigDecimal opVal = new BigDecimal(op.toString());
                        item.setOpPerUnit(opVal);
                        item.setOpAmount(opVal.multiply(
                                new BigDecimal(item.getQuantity())).setScale(2, RoundingMode.HALF_UP));
                    } else {
                        item.setOpPerUnit(null);
                        item.setOpAmount(null);
                    }
                }
                if (io.containsKey("productId") && io.get("productId") != null) {
                    Long newPid = ((Number) io.get("productId")).longValue();
                    item.setProductId(newPid);
                    productRepository.findById(newPid).ifPresent(p -> item.setProductName(p.getName()));
                }
            }
        }

        return order;
    }

    private Expense buildExpenseFromRow(ParsedExpenseRow row, User admin) {
        Expense expense = new Expense();
        expense.setDate(row.date());
        expense.setAdminId(admin.getId());
        expense.setAdminName(admin.getFullName());
        expense.setPaymentMethod(row.paymentMethod());
        expense.setNotes(row.notes());
        expense.setReferenceNumber(row.referenceNumber());
        expense.setImported(true);
        expense.setImportRef(row.referenceNumber());
        expense.setStatus("COMPLETED");

        ExpenseItem item = new ExpenseItem();
        item.setItemDescription("Imported expense");
        item.setAmount(row.amount());
        item.setCategoryId(row.categoryId());
        item.setExpense(expense);
        expense.getItems().add(item);

        expense.recalculateTotal();
        return expense;
    }

    /** Overload that applies review-modal overrides on top of parsed expense data. */
    private Expense buildExpenseFromRow(ParsedExpenseRow row, User admin, Map<String, Object> override) {
        if (override == null) return buildExpenseFromRow(row, admin);

        LocalDate date = row.date();
        Long categoryId = row.categoryId();
        BigDecimal amount = row.amount();
        String notes = row.notes();
        String paymentMethod = row.paymentMethod();

        if (override.containsKey("categoryId") && override.get("categoryId") != null) {
            categoryId = ((Number) override.get("categoryId")).longValue();
        }
        if (override.containsKey("amount")) {
            amount = new BigDecimal(override.get("amount").toString());
        }
        if (override.containsKey("notes")) {
            notes = override.get("notes") != null ? override.get("notes").toString() : null;
        }
        if (override.containsKey("paymentMethod")) {
            paymentMethod = override.get("paymentMethod").toString();
        }

        Expense expense = new Expense();
        expense.setDate(date);
        expense.setAdminId(admin.getId());
        expense.setAdminName(admin.getFullName());
        expense.setPaymentMethod(paymentMethod);
        expense.setNotes(notes);
        expense.setReferenceNumber(row.referenceNumber());
        expense.setImported(true);
        expense.setImportRef(row.referenceNumber());
        expense.setStatus("COMPLETED");

        ExpenseItem item = new ExpenseItem();
        item.setItemDescription("Imported expense");
        item.setAmount(amount);
        item.setCategoryId(categoryId);
        item.setExpense(expense);
        expense.getItems().add(item);

        expense.recalculateTotal();
        return expense;
    }

    // ── SaleAccumulator — mutable; items are added during row processing ───────

    private static class SaleAccumulator {
        private final String receiptNum;
        private final LocalDate date;
        private final String time;
        private final String customer;
        private final String source;
        private final String agentName;
        private final Long agentId;
        private final String paymentMethod;
        private final String platform;
        private final String paymentStatus;
        private final List<ParsedItemRow> items;

        SaleAccumulator(String receiptNum, LocalDate date, String time, String customer,
                        String source, String agentName, Long agentId,
                        String paymentMethod, String platform, String paymentStatus, List<ParsedItemRow> items) {
            this.receiptNum    = receiptNum;
            this.date          = date;
            this.time          = time;
            this.customer      = customer;
            this.source        = source;
            this.agentName     = agentName;
            this.agentId       = agentId;
            this.paymentMethod = paymentMethod;
            this.platform      = platform;
            this.paymentStatus = paymentStatus;
            this.items         = items;
        }

        String receiptNum()    { return receiptNum; }
        LocalDate date()       { return date; }
        String customer()      { return customer; }
        String source()        { return source; }
        String agentName()     { return agentName; }
        Long agentId()         { return agentId; }
        String paymentMethod() { return paymentMethod; }
        String platform()      { return platform; }
        String paymentStatus() { return paymentStatus; }
        List<ParsedItemRow> items() { return items; }
    }
}
