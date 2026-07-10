package rrbm_backend;

import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Agent registry endpoints.
 *
 *  POST  /api/agents                   — Register a new agent
 *  GET   /api/agents?status=ALL        — List agents (ACTIVE | INACTIVE | ALL)
 *  GET   /api/agents/{id}              — Single agent detail
 *  PUT   /api/agents/{id}              — Update mutable fields
 *  PATCH /api/agents/{id}/status       — Toggle ACTIVE ↔ INACTIVE
 */
@RestController
@RequestMapping("/api/agents")
public class AgentController {

    private final AgentRepository              agentRepository;
    private final AgentCommissionRepository    agentCommissionRepository;
    private final CommissionEntryRepository    commissionEntryRepository;
    private final CommissionPeriodRepository   commissionPeriodRepository;
    private final CommissionAdjustmentRepository adjustmentRepository;
    private final OrderRepository              orderRepository;
    private final UserRepository               userRepository;
    private final ActivityLogService           activityLogService;
    private final JwtUtil                      jwtUtil;

    public AgentController(AgentRepository agentRepository,
                           AgentCommissionRepository agentCommissionRepository,
                           CommissionEntryRepository commissionEntryRepository,
                           CommissionPeriodRepository commissionPeriodRepository,
                           CommissionAdjustmentRepository adjustmentRepository,
                           OrderRepository orderRepository,
                           UserRepository userRepository,
                           ActivityLogService activityLogService,
                           JwtUtil jwtUtil) {
        this.agentRepository           = agentRepository;
        this.agentCommissionRepository = agentCommissionRepository;
        this.commissionEntryRepository = commissionEntryRepository;
        this.commissionPeriodRepository = commissionPeriodRepository;
        this.adjustmentRepository      = adjustmentRepository;
        this.orderRepository           = orderRepository;
        this.userRepository            = userRepository;
        this.activityLogService        = activityLogService;
        this.jwtUtil                   = jwtUtil;
    }

    // ── POST /api/agents ───────────────────────────────────────────────────

    @PostMapping
    @Transactional
    public ResponseEntity<?> createAgent(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody Map<String, Object> body) {

        Long adminId = userIdFromHeader(authHeader);
        if (adminId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        String fullName       = trim(body, "fullName");
        String contactNumber  = trim(body, "contactNumber");
        String territory      = trim(body, "territory");

        if (fullName == null || fullName.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "fullName is required"));
        if (contactNumber == null || contactNumber.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "contactNumber is required"));
        if (territory == null || territory.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "territory is required"));

        int year       = LocalDate.now().getYear();
        String prefix  = "AGENT-" + year + "-%";
        int nextSeq    = agentRepository.maxSequenceForYear(prefix) + 1;
        String agentCode = String.format("AGENT-%d-%04d", year, nextSeq);

        Agent agent = new Agent();
        agent.setAgentCode(agentCode);
        agent.setFullName(fullName);
        agent.setContactNumber(contactNumber);
        agent.setEmail(trim(body, "email"));
        agent.setTerritory(territory);
        agent.setNotes(trim(body, "notes"));
        agent.setCreatedBy(adminId);

        Agent saved = agentRepository.save(agent);
        return ResponseEntity.status(201).body(toMap(saved, 0L));
    }

    // ── GET /api/agents?status=ALL&territory=&minCommission=&maxCommission=&registeredFrom=&registeredTo= ─

    @GetMapping
    public ResponseEntity<?> listAgents(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(value = "status",          defaultValue = "ALL") String status,
            @RequestParam(value = "territory",       required = false) String territory,
            @RequestParam(value = "minCommission",   required = false) BigDecimal minCommission,
            @RequestParam(value = "maxCommission",   required = false) BigDecimal maxCommission,
            @RequestParam(value = "registeredFrom",  required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate registeredFrom,
            @RequestParam(value = "registeredTo",    required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate registeredTo) {

        Long adminId = userIdFromHeader(authHeader);
        if (adminId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        boolean hasTerritory = territory != null && !territory.isBlank();
        boolean hasDateRange  = registeredFrom != null || registeredTo != null;

        // Use the most specific DB filter as the base query.
        List<Agent> agents;
        if (hasTerritory) {
            agents = agentRepository.findByTerritoryIgnoreCaseOrderByFullNameAsc(territory.trim());
        } else if (hasDateRange) {
            LocalDate from = registeredFrom != null ? registeredFrom : LocalDate.of(1900, 1, 1);
            LocalDate to   = registeredTo   != null ? registeredTo   : LocalDate.of(9999, 12, 31);
            agents = agentRepository.findByRegistrationDateBetweenOrderByFullNameAsc(from, to);
        } else {
            agents = "ALL".equalsIgnoreCase(status)
                    ? agentRepository.findAll(Sort.by("fullName").ascending())
                    : agentRepository.findByStatusOrderByFullNameAsc(status.toUpperCase());
        }

        // Java-filter status when the base query was territory- or date-based.
        if ((hasTerritory || hasDateRange) && !"ALL".equalsIgnoreCase(status)) {
            final String fs = status.toUpperCase();
            agents = agents.stream()
                    .filter(a -> fs.equals(a.getStatus()))
                    .collect(Collectors.toList());
        }

        // Java-filter date range when territory was the primary DB filter.
        if (hasTerritory && hasDateRange) {
            final LocalDate from = registeredFrom != null ? registeredFrom : LocalDate.of(1900, 1, 1);
            final LocalDate to   = registeredTo   != null ? registeredTo   : LocalDate.of(9999, 12, 31);
            agents = agents.stream()
                    .filter(a -> a.getRegistrationDate() != null
                            && !a.getRegistrationDate().isBefore(from)
                            && !a.getRegistrationDate().isAfter(to))
                    .collect(Collectors.toList());
        }

        // Bulk-fetch all three aggregates in 3 queries instead of N×3.
        List<Long> agentIds = agents.stream().map(Agent::getId).collect(Collectors.toList());

        Map<Long, Long> orderCounts = new HashMap<>();
        for (Object[] row : agentRepository.countOrdersByAgentIds(agentIds)) {
            orderCounts.put((Long) row[0], (Long) row[1]);
        }
        Map<Long, BigDecimal> lifetimeComms  = toDecimalMap(commissionEntryRepository.sumAllOpAmountByAgentIds(agentIds));
        Map<Long, BigDecimal> pendingAmounts = toDecimalMap(commissionEntryRepository.sumPendingOpAmountByAgentIds(agentIds));

        List<Map<String, Object>> result = agents.stream()
                .map(a -> toMap(a,
                        orderCounts.getOrDefault(a.getId(), 0L),
                        lifetimeComms.getOrDefault(a.getId(), BigDecimal.ZERO),
                        pendingAmounts.getOrDefault(a.getId(), BigDecimal.ZERO)))
                .collect(Collectors.toList());

        // Commission range filter applied after lifetimeNetCommission is computed.
        if (minCommission != null || maxCommission != null) {
            result = result.stream()
                    .filter(m -> {
                        BigDecimal lnc = (BigDecimal) m.get("lifetimeNetCommission");
                        if (lnc == null) lnc = BigDecimal.ZERO;
                        if (minCommission != null && lnc.compareTo(minCommission) < 0) return false;
                        if (maxCommission != null && lnc.compareTo(maxCommission) > 0) return false;
                        return true;
                    })
                    .collect(Collectors.toList());
        }

        return ResponseEntity.ok(result);
    }

    // ── GET /api/agents/{id} ───────────────────────────────────────────────

    @GetMapping("/{id}")
    public ResponseEntity<?> getAgent(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long id) {

        Long adminId = userIdFromHeader(authHeader);
        if (adminId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        Agent agent = agentRepository.findById(id).orElse(null);
        if (agent == null) return ResponseEntity.status(404).body(Map.of("error", "Agent not found"));

        return ResponseEntity.ok(toMap(agent, agentRepository.countOrdersByAgentId(id)));
    }

    // ── PUT /api/agents/{id} ───────────────────────────────────────────────

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<?> updateAgent(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {

        Long adminId = userIdFromHeader(authHeader);
        if (adminId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        Agent agent = agentRepository.findById(id).orElse(null);
        if (agent == null) return ResponseEntity.status(404).body(Map.of("error", "Agent not found"));

        // Only mutable fields — agentCode, status, registrationDate, createdAt are immutable
        String fullName      = trim(body, "fullName");
        String contactNumber = trim(body, "contactNumber");
        String territory     = trim(body, "territory");

        if (fullName != null && !fullName.isEmpty())      agent.setFullName(fullName);
        if (contactNumber != null && !contactNumber.isEmpty()) agent.setContactNumber(contactNumber);
        if (territory != null && !territory.isEmpty())   agent.setTerritory(territory);
        if (body.containsKey("email"))                   agent.setEmail(trim(body, "email"));
        if (body.containsKey("notes"))                   agent.setNotes(trim(body, "notes"));

        Agent saved = agentRepository.save(agent);
        return ResponseEntity.ok(toMap(saved, agentRepository.countOrdersByAgentId(id)));
    }

    // ── PATCH /api/agents/{id}/status ─────────────────────────────────────

    @PatchMapping("/{id}/status")
    @Transactional
    public ResponseEntity<?> updateStatus(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {

        Long adminId = userIdFromHeader(authHeader);
        if (adminId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        Agent agent = agentRepository.findById(id).orElse(null);
        if (agent == null) return ResponseEntity.status(404).body(Map.of("error", "Agent not found"));

        String newStatus = trim(body, "status");
        if (!"ACTIVE".equalsIgnoreCase(newStatus) && !"INACTIVE".equalsIgnoreCase(newStatus))
            return ResponseEntity.badRequest().body(Map.of("error", "status must be ACTIVE or INACTIVE"));

        agent.setStatus(newStatus.toUpperCase());
        Agent saved = agentRepository.save(agent);

        User admin = userRepository.findById(adminId).orElse(null);
        String adminName = admin != null ? admin.getFullName() : "Unknown";
        activityLogService.log(adminId, adminName, "AGENT_STATUS_CHANGED",
                "Agent " + agent.getAgentCode() + " status changed to " + newStatus.toUpperCase(),
                "AGENT", String.valueOf(id));

        return ResponseEntity.ok(toMap(saved, agentRepository.countOrdersByAgentId(id)));
    }

    // ── GET /api/agents/{id}/performance ──────────────────────────────────

    @GetMapping("/{id}/performance")
    public ResponseEntity<?> getAgentPerformance(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long id,
            @RequestParam(value = "year",  required = false) Integer year,
            @RequestParam(value = "month", required = false) Integer month) {

        Long adminId = userIdFromHeader(authHeader);
        if (adminId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        Agent agent = agentRepository.findById(id).orElse(null);
        if (agent == null) return ResponseEntity.status(404).body(Map.of("error", "Agent not found"));

        // Query ALL periods (not just released) — fixes dropdown showing only RELEASED periods.
        List<CommissionPeriod> allPeriods = commissionPeriodRepository.findAll();

        BigDecimal lifetimeNetCommission = BigDecimal.ZERO;
        List<Map<String, Object>> commissionSummary = new ArrayList<>();

        for (CommissionPeriod p : allPeriods) {
            // Sum entries for this agent in this period
            List<Object[]> entrySums = commissionEntryRepository
                    .sumByPeriodIdAndAgentId(p.getId(), id);
            boolean hasEntries = !entrySums.isEmpty();

            // Always surface OPEN periods (even with zero entries for this agent) so a newly
            // opened period never silently disappears from the commission view. Periods that
            // are CLOSED/RELEASED with no entries for this agent are still skipped.
            if (!hasEntries && !"OPEN".equals(p.getStatus())) continue;

            BigDecimal totalOp;
            long orderCount;
            if (hasEntries) {
                Object[] entrySum = entrySums.get(0);
                totalOp = entrySum[1] != null ? (BigDecimal) entrySum[1] : BigDecimal.ZERO;
                orderCount = entrySum[2] != null ? ((Number) entrySum[2]).longValue() : 0L;
            } else {
                totalOp = BigDecimal.ZERO;
                orderCount = 0L;
            }

            // Sum adjustments (bonus/deduction) for this agent in this period
            List<CommissionAdjustment> adjs = adjustmentRepository.findByPeriodIdAndAgentId(p.getId(), id);
            BigDecimal totalBonus = adjs.stream()
                    .filter(a -> "BONUS".equals(a.getAdjustmentType()))
                    .map(CommissionAdjustment::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totalDeduction = adjs.stream()
                    .filter(a -> "DEDUCTION".equals(a.getAdjustmentType()))
                    .map(CommissionAdjustment::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal netCommission = totalOp.add(totalBonus).subtract(totalDeduction);
            lifetimeNetCommission = lifetimeNetCommission.add(netCommission);

            // Check payment status (only exists for RELEASED periods via agent_commissions)
            AgentCommission ac = agentCommissionRepository
                    .findByAgentIdAndPeriodId(id, p.getId()).orElse(null);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("periodId",         p.getId());
            row.put("periodCode",       p.getPeriodCode());
            row.put("startDate",        p.getStartDate().toString());
            row.put("endDate",          p.getEndDate().toString());
            row.put("totalOp",          totalOp);
            row.put("totalBonus",       totalBonus);
            row.put("totalDeduction",   totalDeduction);
            row.put("netCommission",    netCommission);
            row.put("orderCount",       orderCount);
            row.put("releasedAt",       p.getReleasedAt() != null ? p.getReleasedAt().toString() : null);
            row.put("status",           ac != null ? ac.getStatus() : p.getStatus());
            row.put("paymentMethod",    ac != null ? ac.getPaymentMethod() : null);
            row.put("paymentReference", ac != null ? ac.getPaymentReference() : null);
            row.put("paymentDate",      ac != null && ac.getPaymentDate() != null ? ac.getPaymentDate().toString() : null);
            row.put("paidAt",           ac != null && ac.getPaidAt() != null ? ac.getPaidAt().toString() : null);

            commissionSummary.add(row);
        }

        // Apply optional period overlap filter for commissionSummary.
        if (year != null && month != null) {
            LocalDate firstDay = LocalDate.of(year, month, 1);
            LocalDate lastDay  = firstDay.withDayOfMonth(firstDay.lengthOfMonth());
            commissionSummary = commissionSummary.stream()
                    .filter(row -> {
                        LocalDate start = LocalDate.parse((String) row.get("startDate"));
                        LocalDate end   = LocalDate.parse((String) row.get("endDate"));
                        return !end.isBefore(firstDay) && !start.isAfter(lastDay);
                    })
                    .collect(Collectors.toList());
        }

        // Sort newest first (null releasedAt sorts last)
        commissionSummary.sort((a, b) -> {
            String ra = (String) a.get("releasedAt");
            String rb = (String) b.get("releasedAt");
            if (ra == null && rb == null) return 0;
            if (ra == null) return 1;   // nulls sort last
            if (rb == null) return -1;
            return rb.compareTo(ra);
        });

        long totalOrders = agentRepository.countOrdersByAgentId(id);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agentId",               agent.getId());
        result.put("agentCode",             agent.getAgentCode());
        result.put("fullName",              agent.getFullName());
        result.put("totalOrders",           totalOrders);
        result.put("commissionSummary",     commissionSummary);
        result.put("lifetimeNetCommission", lifetimeNetCommission);

        return ResponseEntity.ok(result);
    }

    // ── GET /api/agents/{id}/orders?periodId= ──────────────────────────────

    @GetMapping("/{id}/orders")
    public ResponseEntity<?> getAgentOrders(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long id,
            @RequestParam(value = "periodId", required = false) Long periodId) {

        Long adminId = userIdFromHeader(authHeader);
        if (adminId == null) return ResponseEntity.status(401).body(Map.of("error", "Unauthorized"));

        Agent agent = agentRepository.findById(id).orElse(null);
        if (agent == null) return ResponseEntity.status(404).body(Map.of("error", "Agent not found"));

        List<Order> allOrders = orderRepository.findByAgentIdWithItems(id);

        // If periodId is provided, filter orders whose orderDate falls within that period.
        if (periodId != null) {
            CommissionPeriod period = commissionPeriodRepository.findById(periodId).orElse(null);
            if (period == null)
                return ResponseEntity.status(404).body(Map.of("error", "Commission period not found"));

            LocalDate pStart = period.getStartDate();
            LocalDate pEnd   = period.getEndDate();
            allOrders = allOrders.stream()
                    .filter(o -> o.getCreatedAt() != null
                            && !o.getCreatedAt().toLocalDate().isBefore(pStart)
                            && !o.getCreatedAt().toLocalDate().isAfter(pEnd))
                    .collect(Collectors.toList());
        }

        BigDecimal totalRevenue = BigDecimal.ZERO;
        BigDecimal totalOp      = BigDecimal.ZERO;

        List<Map<String, Object>> ordersList = new ArrayList<>();
        for (Order o : allOrders) {
            totalRevenue = totalRevenue.add(o.getTotal() != null ? o.getTotal() : BigDecimal.ZERO);

            List<Map<String, Object>> itemsList = new ArrayList<>();
            BigDecimal orderOp = BigDecimal.ZERO;
            for (var item : o.getItems()) {
                BigDecimal opSubtotal = item.getOpAmount() != null ? item.getOpAmount() : BigDecimal.ZERO;
                orderOp = orderOp.add(opSubtotal);
                itemsList.add(Map.of(
                        "productName",  item.getProductName() != null ? item.getProductName() : "",
                        "quantity",     item.getQuantity() != null ? item.getQuantity() : 0,
                        "unitPrice",    item.getUnitPrice()  != null ? item.getUnitPrice()  : BigDecimal.ZERO,
                        "basePrice",    item.getBasePrice()  != null ? item.getBasePrice()  : BigDecimal.ZERO,
                        "opPerUnit",    item.getOpPerUnit()  != null ? item.getOpPerUnit()  : BigDecimal.ZERO,
                        "opSubtotal",   opSubtotal
                ));
            }
            totalOp = totalOp.add(orderOp);

            ordersList.add(Map.of(
                    "orderId",   o.getId(),
                    "date",      o.getCreatedAt() != null ? o.getCreatedAt().toLocalDate().toString() : "",
                    "customer",  o.getCustomerName() != null ? o.getCustomerName() : "",
                    "source",    o.getSource()    != null ? o.getSource()    : "",
                    "status",    o.getStatus()    != null ? o.getStatus()    : "",
                    "items",     itemsList,
                    "total",     o.getTotal()     != null ? o.getTotal()     : BigDecimal.ZERO,
                    "totalOp",   orderOp
            ));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("orders", ordersList);
        result.put("summary", Map.of(
                "totalOrders",  allOrders.size(),
                "totalRevenue", totalRevenue,
                "totalOp",      totalOp
        ));

        return ResponseEntity.ok(result);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private Long userIdFromHeader(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return null;
        return jwtUtil.extractUserId(authHeader.substring(7));
    }

    private String trim(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v != null ? v.toString().trim() : null;
    }

    private BigDecimal lifetimeNetCommission(Long agentId) {
        return commissionEntryRepository.sumAllOpAmountByAgentId(agentId);
    }

    private Map<Long, BigDecimal> toDecimalMap(List<Object[]> rows) {
        Map<Long, BigDecimal> map = new HashMap<>();
        for (Object[] row : rows) {
            BigDecimal val = row[1] instanceof BigDecimal
                    ? (BigDecimal) row[1]
                    : new BigDecimal(row[1].toString());
            map.put((Long) row[0], val);
        }
        return map;
    }

    private Map<String, Object> toMap(Agent agent, long totalOrders) {
        return toMap(agent, totalOrders, lifetimeNetCommission(agent.getId()));
    }

    private Map<String, Object> toMap(Agent agent, long totalOrders, BigDecimal lifetimeNet) {
        return toMap(agent, totalOrders, lifetimeNet,
                commissionEntryRepository.sumPendingOpAmountByAgentId(agent.getId()));
    }

    private Map<String, Object> toMap(Agent agent, long totalOrders,
                                      BigDecimal lifetimeNet, BigDecimal pending) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",                     agent.getId());
        m.put("agentCode",              agent.getAgentCode());
        m.put("fullName",               agent.getFullName());
        m.put("contactNumber",          agent.getContactNumber());
        m.put("email",                  agent.getEmail());
        m.put("territory",              agent.getTerritory());
        m.put("status",                 agent.getStatus());
        m.put("registrationDate",       agent.getRegistrationDate() != null
                                        ? agent.getRegistrationDate().toString() : null);
        m.put("notes",                  agent.getNotes());
        m.put("totalOrders",            totalOrders);
        m.put("lifetimeNetCommission",  lifetimeNet);
        m.put("pendingCommission",      pending);
        return m;
    }
}
