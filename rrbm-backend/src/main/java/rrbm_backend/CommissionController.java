package rrbm_backend;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/commissions")
public class CommissionController {

    private final CommissionPeriodRepository     periodRepository;
    private final CommissionEntryRepository      entryRepository;
    private final CommissionAdjustmentRepository adjustmentRepository;
    private final AgentCommissionRepository      agentCommissionRepository;
    private final AgentRepository                agentRepository;
    private final OrderRepository                orderRepository;
    private final UserRepository                 userRepository;
    private final ActivityLogService             activityLogService;
    private final CommissionService              commissionService;
    private final JwtUtil                        jwtUtil;
    private final BCryptPasswordEncoder          passwordEncoder = new BCryptPasswordEncoder();

    // RRBM logo (classpath resource) inlined as a base64 data URI so exported statements are
    // self-contained (they render in a popup/blob window where relative asset URLs won't resolve).
    private static volatile String LOGO_DATA_URI;
    private static String logoDataUri() {
        if (LOGO_DATA_URI == null) {
            synchronized (CommissionController.class) {
                if (LOGO_DATA_URI == null) {
                    String uri = "";
                    try (java.io.InputStream in =
                                 CommissionController.class.getResourceAsStream("/rrbm-logo.png")) {
                        if (in != null) {
                            uri = "data:image/png;base64,"
                                    + java.util.Base64.getEncoder().encodeToString(in.readAllBytes());
                        }
                    } catch (Exception ignored) { /* fall back to text mark */ }
                    LOGO_DATA_URI = uri;
                }
            }
        }
        return LOGO_DATA_URI;
    }

    public CommissionController(CommissionPeriodRepository periodRepository,
                                CommissionEntryRepository entryRepository,
                                CommissionAdjustmentRepository adjustmentRepository,
                                AgentCommissionRepository agentCommissionRepository,
                                AgentRepository agentRepository,
                                OrderRepository orderRepository,
                                UserRepository userRepository,
                                ActivityLogService activityLogService,
                                CommissionService commissionService,
                                JwtUtil jwtUtil) {
        this.periodRepository          = periodRepository;
        this.entryRepository           = entryRepository;
        this.adjustmentRepository      = adjustmentRepository;
        this.agentCommissionRepository = agentCommissionRepository;
        this.agentRepository           = agentRepository;
        this.orderRepository           = orderRepository;
        this.userRepository            = userRepository;
        this.activityLogService        = activityLogService;
        this.commissionService         = commissionService;
        this.jwtUtil                   = jwtUtil;
    }

    private Long userIdFromHeader(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return null;
        return jwtUtil.extractUserId(authHeader.substring(7));
    }

    // ── POST /api/commissions/periods — open a new commission period ──────────

    @PostMapping("/periods")
    public ResponseEntity<?> createPeriod(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        Long userId = userIdFromHeader(authHeader);
        if (userId == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Authentication required"));

        String startStr = (String) body.get("startDate");
        String endStr   = (String) body.get("endDate");
        String notes    = (String) body.get("notes");

        if (startStr == null || endStr == null)
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "startDate and endDate are required"));

        LocalDate startDate;
        LocalDate endDate;
        try {
            startDate = LocalDate.parse(startStr);
            endDate   = LocalDate.parse(endStr);
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Invalid date format — use YYYY-MM-DD"));
        }

        if (!startDate.isBefore(endDate))
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "startDate must be before endDate"));

        List<CommissionPeriod> overlapping = periodRepository
                .findByStartDateLessThanEqualAndEndDateGreaterThanEqual(endDate, startDate)
                .stream()
                .filter(p -> "OPEN".equals(p.getStatus()))
                .collect(Collectors.toList());
        if (!overlapping.isEmpty())
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "An open period already overlaps the requested date range"));

        String yearMonth  = startDate.getYear() + "-" + String.format("%02d", startDate.getMonthValue());
        long   count      = periodRepository.countByPeriodCodeStartingWith(yearMonth + "-");
        String periodCode = yearMonth + "-" + (char) ('A' + count);

        CommissionPeriod period = new CommissionPeriod();
        period.setPeriodCode(periodCode);
        period.setStartDate(startDate);
        period.setEndDate(endDate);
        period.setNotes(notes);
        period.setCreatedBy(userId);
        CommissionPeriod saved = periodRepository.save(period);

        // Backfill commission entries for existing orders in this period's date range
        Map<String, Object> backfillStats = commissionService.backfillEntriesForPeriod(saved);

        Map<String, Object> response = periodToMap(saved, true);
        response.put("backfill", backfillStats);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // ── PUT /api/commissions/periods/{id} — edit an OPEN period's dates/notes ──

    @Transactional
    @PutMapping("/periods/{id}")
    public ResponseEntity<?> updatePeriod(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        Long userId = userIdFromHeader(authHeader);
        if (userId == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Authentication required"));

        CommissionPeriod period = periodRepository.findById(id).orElse(null);
        if (period == null)
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", "Period not found"));

        if (!"OPEN".equals(period.getStatus()))
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Only an OPEN period can be edited"));

        String startStr = (String) body.get("startDate");
        String endStr   = (String) body.get("endDate");
        if (startStr == null || endStr == null)
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "startDate and endDate are required"));

        LocalDate startDate;
        LocalDate endDate;
        try {
            startDate = LocalDate.parse(startStr);
            endDate   = LocalDate.parse(endStr);
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Invalid date format — use YYYY-MM-DD"));
        }

        if (!startDate.isBefore(endDate))
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "startDate must be before endDate"));

        // Overlap check against other OPEN periods (exclude this one).
        List<CommissionPeriod> overlapping = periodRepository
                .findByStartDateLessThanEqualAndEndDateGreaterThanEqual(endDate, startDate)
                .stream()
                .filter(p -> "OPEN".equals(p.getStatus()) && !p.getId().equals(id))
                .collect(Collectors.toList());
        if (!overlapping.isEmpty())
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "An open period already overlaps the requested date range"));

        period.setStartDate(startDate);
        period.setEndDate(endDate);
        if (body.containsKey("notes")) period.setNotes((String) body.get("notes"));
        CommissionPeriod saved = periodRepository.save(period);

        // Re-sort entries to the new range: drop out-of-range PENDING entries, backfill new ones.
        Map<String, Object> resync = commissionService.resyncOpenPeriodEntries(saved);

        Map<String, Object> response = periodToMap(saved, true);
        response.put("resync", resync);
        return ResponseEntity.ok(response);
    }

    // ── DELETE /api/commissions/periods/{id} — delete an EMPTY open period ─────

    @Transactional
    @DeleteMapping("/periods/{id}")
    public ResponseEntity<?> deletePeriod(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        Long userId = userIdFromHeader(authHeader);
        if (userId == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Authentication required"));

        CommissionPeriod period = periodRepository.findById(id).orElse(null);
        if (period == null)
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", "Period not found"));

        if (!"OPEN".equals(period.getStatus()))
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Only an OPEN period can be deleted"));

        long entryCount = entryRepository.countByPeriodId(id);
        long adjCount   = adjustmentRepository.countByPeriodId(id);
        if (entryCount > 0 || adjCount > 0)
            return ResponseEntity.badRequest().body(Map.of("message",
                    "This period has " + entryCount + " commission entr" + (entryCount == 1 ? "y" : "ies")
                    + " and " + adjCount + " adjustment(s); it cannot be deleted. "
                    + "Edit its dates instead — commissions are never deleted."));

        periodRepository.deleteById(id);
        return ResponseEntity.ok(Map.of("message", "Period " + period.getPeriodCode() + " deleted"));
    }

    // ── GET /api/commissions/periods — list all periods with totals ───────────

    @GetMapping("/periods")
    public ResponseEntity<?> listPeriods(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        Long userId = userIdFromHeader(authHeader);
        if (userId == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Authentication required"));

        List<CommissionPeriod> periods = periodRepository.findAll();
        periods.sort((a, b) -> b.getStartDate().compareTo(a.getStartDate()));

        List<Map<String, Object>> result = new ArrayList<>();
        for (CommissionPeriod p : periods) {
            List<Object[]> agentSums = entryRepository.sumByAgentForPeriod(p.getId());

            BigDecimal totalOp = agentSums.stream()
                    .map(r -> r[1] != null ? (BigDecimal) r[1] : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            // Net O.P. adds bonuses and subtracts deductions across all agents for the period.
            List<CommissionAdjustment> adjs = adjustmentRepository.findByPeriodId(p.getId());
            BigDecimal bonusSum = adjs.stream()
                    .filter(a -> "BONUS".equals(a.getAdjustmentType()))
                    .map(CommissionAdjustment::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal deductionSum = adjs.stream()
                    .filter(a -> "DEDUCTION".equals(a.getAdjustmentType()))
                    .map(CommissionAdjustment::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal netOp = totalOp.add(bonusSum).subtract(deductionSum);

            Map<String, Object> row = new HashMap<>();
            row.put("id",          p.getId());
            row.put("periodCode",  p.getPeriodCode());
            row.put("startDate",   p.getStartDate().toString());
            row.put("endDate",     p.getEndDate().toString());
            row.put("status",      p.getStatus());
            row.put("totalAgents", (long) agentSums.size());
            row.put("totalOp",     totalOp);
            row.put("netOp",       netOp);
            result.add(row);
        }
        return ResponseEntity.ok(result);
    }

    // ── GET /api/commissions/periods/{id} — detail + per-agent breakdown ──────

    @GetMapping("/periods/{id}")
    public ResponseEntity<?> getPeriod(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        Long userId = userIdFromHeader(authHeader);
        if (userId == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Authentication required"));

        CommissionPeriod period = periodRepository.findById(id).orElse(null);
        if (period == null)
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", "Period not found"));

        Map<String, Object> result = periodToMap(period, true);

        List<Object[]> agentSums = entryRepository.sumByAgentForPeriod(id);
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Object[] row : agentSums) {
            Long       agentId = (Long) row[0];
            BigDecimal totalOp = row[1] != null ? (BigDecimal) row[1] : BigDecimal.ZERO;

            Agent agent = agentRepository.findById(agentId).orElse(null);

            List<CommissionEntry> agentEntries = entryRepository.findByPeriodIdAndAgentId(id, agentId);
            long orderCount = agentEntries.stream()
                    .map(CommissionEntry::getOrderId)
                    .filter(oid -> oid != null)
                    .distinct()
                    .count();

            List<CommissionAdjustment> agentAdjs = adjustmentRepository.findByPeriodIdAndAgentId(id, agentId);
            BigDecimal bonusSum = agentAdjs.stream()
                    .filter(a -> "BONUS".equals(a.getAdjustmentType()))
                    .map(CommissionAdjustment::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal deductionSum = agentAdjs.stream()
                    .filter(a -> "DEDUCTION".equals(a.getAdjustmentType()))
                    .map(CommissionAdjustment::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totalAdjustments = bonusSum.subtract(deductionSum);
            BigDecimal netCommission    = totalOp.add(totalAdjustments);

            Map<String, Object> entryRow = new HashMap<>();
            entryRow.put("agentId",          agentId);
            entryRow.put("agentCode",        agent != null ? agent.getAgentCode() : null);
            entryRow.put("fullName",         agent != null ? agent.getFullName()  : null);
            entryRow.put("orderCount",       orderCount);
            entryRow.put("totalOp",          totalOp);
            entryRow.put("totalAdjustments", totalAdjustments);
            entryRow.put("netCommission",    netCommission);
            entries.add(entryRow);
        }
        result.put("entries", entries);

        return ResponseEntity.ok(result);
    }

    // ── POST /api/commissions/periods/{id}/close ──────────────────────────────

    @PostMapping("/periods/{id}/close")
    public ResponseEntity<?> closePeriod(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        Long userId = userIdFromHeader(authHeader);
        if (userId == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Authentication required"));

        CommissionPeriod period = periodRepository.findById(id).orElse(null);
        if (period == null)
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", "Period not found"));

        if (!"OPEN".equals(period.getStatus()))
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Period is already " + period.getStatus().toLowerCase()));

        period.setStatus("CLOSED");
        period.setClosedAt(OffsetDateTime.now());
        period.setClosedBy(userId);
        periodRepository.save(period);

        return ResponseEntity.ok(periodToMap(period, true));
    }

    // ── POST /api/commissions/periods/{id}/release — requires security key ────

    @Transactional
    @PostMapping("/periods/{id}/release")
    public ResponseEntity<?> releasePeriod(
            @PathVariable Long id,
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        Long userId = userIdFromHeader(authHeader);
        if (userId == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Authentication required"));

        String secKey = body.getOrDefault("adminSecurityKey", "").trim();
        if (secKey.isEmpty())
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "adminSecurityKey is required"));

        CommissionPeriod period = periodRepository.findById(id).orElse(null);
        if (period == null)
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", "Period not found"));

        if (!"CLOSED".equals(period.getStatus()))
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Only a CLOSED period can be released"));

        User caller = userRepository.findById(userId).orElse(null);
        if (caller == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "User not found"));
        if (caller.getAdminSecurityKey() == null)
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message",
                            "No admin security key has been set for your account. Ask your Super Admin to assign one."));
        if (!passwordEncoder.matches(secKey, caller.getAdminSecurityKey()))
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "Invalid security key"));

        period.setStatus("RELEASED");
        period.setReleasedAt(OffsetDateTime.now());
        period.setReleasedBy(userId);
        // saveAndFlush: force the UPDATE to reach the DB before releaseAllByPeriodId
        // calls em.clear() (clearAutomatically = true), which would otherwise evict
        // the dirty entity from the L1 cache before it could be flushed on commit.
        periodRepository.saveAndFlush(period);

        entryRepository.releaseAllByPeriodId(id);

        // Upsert one agent_commissions row per agent who has entries or adjustments in this period.
        List<Object[]> opByAgentRows = entryRepository.sumReleasedOpByAgentForPeriod(id);
        List<CommissionAdjustment> allAdjs = adjustmentRepository.findByPeriodId(id);

        Map<Long, BigDecimal> opByAgent = new HashMap<>();
        Set<Long> agentIds = new HashSet<>();
        for (Object[] row : opByAgentRows) {
            Long       aId = (Long) row[0];
            BigDecimal op  = row[1] != null ? (BigDecimal) row[1] : BigDecimal.ZERO;
            opByAgent.put(aId, op);
            agentIds.add(aId);
        }
        for (CommissionAdjustment a : allAdjs) {
            agentIds.add(a.getAgentId());
        }

        for (Long aId : agentIds) {
            BigDecimal totalOp = opByAgent.getOrDefault(aId, BigDecimal.ZERO);

            BigDecimal totalBonus = BigDecimal.ZERO;
            BigDecimal totalDeduction = BigDecimal.ZERO;
            for (CommissionAdjustment a : allAdjs) {
                if (!aId.equals(a.getAgentId())) continue;
                if ("BONUS".equals(a.getAdjustmentType())) {
                    totalBonus = totalBonus.add(a.getAmount());
                } else if ("DEDUCTION".equals(a.getAdjustmentType())) {
                    totalDeduction = totalDeduction.add(a.getAmount());
                }
            }
            BigDecimal netCommission = totalOp.add(totalBonus).subtract(totalDeduction);

            AgentCommission ac = agentCommissionRepository
                    .findByAgentIdAndPeriodId(aId, id)
                    .orElse(new AgentCommission());
            ac.setAgentId(aId);
            ac.setPeriodId(id);
            ac.setPeriodCode(period.getPeriodCode());
            ac.setStartDate(period.getStartDate());
            ac.setEndDate(period.getEndDate());
            ac.setTotalOp(totalOp);
            ac.setTotalBonus(totalBonus);
            ac.setTotalDeduction(totalDeduction);
            ac.setNetCommission(netCommission);
            ac.setReleasedAt(period.getReleasedAt());
            agentCommissionRepository.save(ac);
        }

        return ResponseEntity.ok(periodToMap(period, true));
    }

    // ── POST /api/commissions/periods/{id}/adjustments — add bonus/deduction ──

    @PostMapping("/periods/{id}/adjustments")
    public ResponseEntity<?> addAdjustment(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        Long userId = userIdFromHeader(authHeader);
        if (userId == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Authentication required"));

        CommissionPeriod period = periodRepository.findById(id).orElse(null);
        if (period == null)
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", "Period not found"));

        if ("RELEASED".equals(period.getStatus()))
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Cannot add adjustments to a RELEASED period"));

        Object agentIdObj = body.get("agentId");
        if (agentIdObj == null)
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "agentId is required"));
        Long agentId = ((Number) agentIdObj).longValue();

        String adjustmentType = (String) body.get("adjustmentType");
        if (!"BONUS".equals(adjustmentType) && !"DEDUCTION".equals(adjustmentType))
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "adjustmentType must be BONUS or DEDUCTION"));

        Object amountObj = body.get("amount");
        if (amountObj == null)
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "amount is required"));
        BigDecimal amount;
        try {
            amount = new BigDecimal(amountObj.toString());
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Invalid amount value"));
        }

        String reason = body.get("reason") != null ? body.get("reason").toString().trim() : "";
        if (reason.isEmpty())
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "reason is required"));

        if (!agentRepository.existsById(agentId))
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", "Agent not found"));

        CommissionAdjustment adj = new CommissionAdjustment();
        adj.setPeriodId(id);
        adj.setAgentId(agentId);
        adj.setAdjustmentType(adjustmentType);
        adj.setAmount(amount);
        adj.setReason(reason);
        adj.setCreatedBy(userId);

        CommissionAdjustment saved = adjustmentRepository.save(adj);
        return ResponseEntity.status(HttpStatus.CREATED).body(adjustmentToMap(saved));
    }

    // ── GET /api/commissions/periods/{id}/agents/{agentId}/statement ──────────

    @GetMapping("/periods/{id}/agents/{agentId}/statement")
    public ResponseEntity<?> getAgentStatement(
            @PathVariable Long id,
            @PathVariable Long agentId,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        Long userId = userIdFromHeader(authHeader);
        if (userId == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Authentication required"));

        CommissionPeriod period = periodRepository.findById(id).orElse(null);
        if (period == null)
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", "Period not found"));

        Agent agent = agentRepository.findById(agentId).orElse(null);
        if (agent == null)
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", "Agent not found"));

        // Period summary (lightweight — no audit timestamps)
        Map<String, Object> periodMap = new HashMap<>();
        periodMap.put("id",         period.getId());
        periodMap.put("periodCode", period.getPeriodCode());
        periodMap.put("startDate",  period.getStartDate().toString());
        periodMap.put("endDate",    period.getEndDate().toString());
        periodMap.put("status",     period.getStatus());

        // Agent info
        Map<String, Object> agentMap = new HashMap<>();
        agentMap.put("id",            agent.getId());
        agentMap.put("agentCode",     agent.getAgentCode());
        agentMap.put("fullName",      agent.getFullName());
        agentMap.put("contactNumber", agent.getContactNumber());

        // Commission entries
        List<CommissionEntry> entries = entryRepository.findByPeriodIdAndAgentId(id, agentId);
        List<Map<String, Object>> entriesList = new ArrayList<>();
        BigDecimal totalOp = BigDecimal.ZERO;
        for (CommissionEntry e : entries) {
            Map<String, Object> em = new HashMap<>();
            em.put("orderId",     e.getOrderId());
            em.put("orderDate",   e.getOrderDate() != null ? e.getOrderDate().toString() : null);
            em.put("productName", e.getProductName());
            em.put("quantity",    e.getQuantity());
            em.put("basePrice",   e.getBasePrice());
            em.put("opRate",      e.getOpRate());
            em.put("opAmount",    e.getOpAmount());
            em.put("status",      e.getStatus());
            entriesList.add(em);
            if (e.getOpAmount() != null) totalOp = totalOp.add(e.getOpAmount());
        }

        // Adjustments
        List<CommissionAdjustment> adjustments = adjustmentRepository.findByPeriodIdAndAgentId(id, agentId);
        List<Map<String, Object>> adjList = new ArrayList<>();
        BigDecimal bonusSum     = BigDecimal.ZERO;
        BigDecimal deductionSum = BigDecimal.ZERO;
        for (CommissionAdjustment a : adjustments) {
            adjList.add(adjustmentToMap(a));
            if ("BONUS".equals(a.getAdjustmentType())) {
                bonusSum = bonusSum.add(a.getAmount());
            } else if ("DEDUCTION".equals(a.getAdjustmentType())) {
                deductionSum = deductionSum.add(a.getAmount());
            }
        }

        BigDecimal totalAdjustments = bonusSum.subtract(deductionSum);
        BigDecimal netCommission    = totalOp.add(totalAdjustments);

        Map<String, Object> summary = new HashMap<>();
        summary.put("entryCount",        entries.size());
        summary.put("totalOp",           totalOp);
        summary.put("totalAdjustments",  totalAdjustments);
        summary.put("netCommission",     netCommission);

        Map<String, Object> result = new HashMap<>();
        result.put("period",      periodMap);
        result.put("agent",       agentMap);
        result.put("entries",     entriesList);
        result.put("adjustments", adjList);
        result.put("summary",     summary);

        return ResponseEntity.ok(result);
    }

    // ── GET /api/agents/{id}/commissions/breakdown?periodId= — order-level commission detail ─

    @GetMapping("/agents/{id}/commissions/breakdown")
    public ResponseEntity<?> getCommissionBreakdown(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable Long id,
            @RequestParam Long periodId) {

        Long userId = userIdFromHeader(authHeader);
        if (userId == null)
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));

        Agent agent = agentRepository.findById(id).orElse(null);
        if (agent == null)
            return ResponseEntity.status(404).body(Map.of("error", "Agent not found"));

        CommissionPeriod period = periodRepository.findById(periodId).orElse(null);
        if (period == null)
            return ResponseEntity.status(404).body(Map.of("error", "Period not found"));

        List<CommissionEntry> allEntries = entryRepository.findByPeriodIdAndAgentId(periodId, id);
        if (allEntries.isEmpty()) {
            Map<String, Object> emptyResult = new LinkedHashMap<>();
            emptyResult.put("agentId",        agent.getId());
            emptyResult.put("agentName",      agent.getFullName());
            emptyResult.put("period",         period.getPeriodCode());
            emptyResult.put("orders",         new ArrayList<>());
            emptyResult.put("totalCommission", BigDecimal.ZERO);
            return ResponseEntity.ok(emptyResult);
        }

        // Sort by date then id
        allEntries.sort((a, b) -> {
            int c = a.getOrderDate().compareTo(b.getOrderDate());
            if (c != 0) return c;
            String oa = a.getOrderId()  != null ? a.getOrderId()  : "";
            String ob = b.getOrderId()  != null ? b.getOrderId()  : "";
            return oa.compareTo(ob);
        });

        // Group by orderId
        Map<String, List<CommissionEntry>> grouped = new LinkedHashMap<>();
        for (CommissionEntry e : allEntries) {
            String key = e.getOrderId() != null ? e.getOrderId() : "__none__";
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(e);
        }

        List<Map<String, Object>> ordersList = new ArrayList<>();
        BigDecimal grandTotal = BigDecimal.ZERO;

        for (Map.Entry<String, List<CommissionEntry>> grp : grouped.entrySet()) {
            String oid = grp.getKey();
            List<CommissionEntry> groupEntries = grp.getValue();
            CommissionEntry first = groupEntries.get(0);

            String customerName = "";
            if (!oid.equals("__none__")) {
                Optional<Order> orderOpt = orderRepository.findById(oid);
                if (orderOpt.isPresent()) {
                    customerName = orderOpt.get().getCustomerName();
                }
            }

            List<Map<String, Object>> itemsList = new ArrayList<>();
            BigDecimal orderTotal = BigDecimal.ZERO;

            for (CommissionEntry e : groupEntries) {
                BigDecimal basePrice = e.getBasePrice() != null ? e.getBasePrice() : BigDecimal.ZERO;
                BigDecimal opAmount = e.getOpAmount()  != null ? e.getOpAmount()  : BigDecimal.ZERO;
                int qty = e.getQuantity() != null ? e.getQuantity() : 0;

                // U15: opPerUnit stored directly; fallback for legacy entries
                BigDecimal opPerUnit;
                if (e.getOpPerUnit() != null) {
                    opPerUnit = e.getOpPerUnit();
                } else if (qty > 0) {
                    opPerUnit = opAmount.divide(BigDecimal.valueOf(qty), 5, RoundingMode.HALF_UP);
                } else {
                    opPerUnit = BigDecimal.ZERO;
                }

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("productName",      e.getProductName() != null ? e.getProductName() : "");
                item.put("quantity",         qty);
                item.put("unitPrice",        basePrice);
                item.put("opPerUnit",        opPerUnit);
                item.put("opSubtotal",       opAmount);
                item.put("commissionRate",   BigDecimal.ZERO);  // deprecated in U15
                item.put("commissionAmount", opAmount);
                itemsList.add(item);

                orderTotal = orderTotal.add(opAmount);
            }

            Map<String, Object> orderObj = new LinkedHashMap<>();
            orderObj.put("orderId",       oid.equals("__none__") ? "" : oid);
            orderObj.put("date",          first.getOrderDate() != null ? first.getOrderDate().toString() : "");
            orderObj.put("customer",      customerName);
            orderObj.put("items",         itemsList);
            orderObj.put("totalOp",       orderTotal);
            orderObj.put("totalCommission", orderTotal);
            ordersList.add(orderObj);

            grandTotal = grandTotal.add(orderTotal);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agentId",        agent.getId());
        result.put("agentName",      agent.getFullName());
        result.put("period",         period.getPeriodCode());
        result.put("orders",         ordersList);
        result.put("totalCommission", grandTotal);

        return ResponseEntity.ok(result);
    }

    // ── POST /api/commissions/periods/{id}/agents/{agentId}/pay ──────────────

    @PostMapping("/periods/{id}/agents/{agentId}/pay")
    public ResponseEntity<?> recordPayment(
            @PathVariable Long id,
            @PathVariable Long agentId,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        Long userId = userIdFromHeader(authHeader);
        if (userId == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Authentication required"));

        CommissionPeriod period = periodRepository.findById(id).orElse(null);
        if (period == null)
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", "Period not found"));

        if (!"RELEASED".equals(period.getStatus()))
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Only a RELEASED period can have payments recorded"));

        AgentCommission ac = agentCommissionRepository
                .findByAgentIdAndPeriodId(agentId, id).orElse(null);
        if (ac == null)
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", "No commission record found for this agent in the period"));

        if ("PAID".equals(ac.getStatus()))
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Commission is already marked as PAID"));

        String paymentMethod    = body.get("paymentMethod")    != null
                ? body.get("paymentMethod").toString().trim()    : null;
        String paymentReference = body.get("paymentReference") != null
                ? body.get("paymentReference").toString().trim() : null;
        String paymentDateStr   = body.get("paymentDate")      != null
                ? body.get("paymentDate").toString()             : null;
        String secKey           = body.get("adminSecurityKey") != null
                ? body.get("adminSecurityKey").toString().trim() : "";

        if (paymentMethod == null || paymentMethod.isEmpty())
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "paymentMethod is required"));
        if (paymentDateStr == null || paymentDateStr.isEmpty())
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "paymentDate is required"));
        if (secKey.isEmpty())
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "adminSecurityKey is required"));

        LocalDate paymentDate;
        try {
            paymentDate = LocalDate.parse(paymentDateStr);
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Invalid paymentDate format — use YYYY-MM-DD"));
        }

        User caller = userRepository.findById(userId).orElse(null);
        if (caller == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "User not found"));
        if (caller.getAdminSecurityKey() == null)
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message",
                            "No admin security key has been set for your account. Ask your Super Admin to assign one."));
        if (!passwordEncoder.matches(secKey, caller.getAdminSecurityKey()))
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "Invalid security key"));

        ac.setStatus("PAID");
        ac.setPaymentMethod(paymentMethod);
        ac.setPaymentReference(paymentReference);
        ac.setPaymentDate(paymentDate);
        ac.setPaidBy(userId);
        ac.setPaidAt(OffsetDateTime.now());
        agentCommissionRepository.save(ac);

        activityLogService.log(userId, caller.getFullName(), "COMMISSION_PAID",
                "Commission for agent " + agentId + " in period " + period.getPeriodCode()
                        + " marked PAID via " + paymentMethod,
                "AGENT_COMMISSION", String.valueOf(ac.getId()));

        return ResponseEntity.ok(ac.toMap());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> periodToMap(CommissionPeriod p, boolean includeAudit) {
        Map<String, Object> m = new HashMap<>();
        m.put("id",         p.getId());
        m.put("periodCode", p.getPeriodCode());
        m.put("startDate",  p.getStartDate().toString());
        m.put("endDate",    p.getEndDate().toString());
        m.put("status",     p.getStatus());
        m.put("notes",      p.getNotes());
        if (includeAudit) {
            m.put("createdAt",  p.getCreatedAt()  != null ? p.getCreatedAt().toString()  : null);
            m.put("createdBy",  p.getCreatedBy());
            m.put("closedAt",   p.getClosedAt()   != null ? p.getClosedAt().toString()   : null);
            m.put("closedBy",   p.getClosedBy());
            m.put("releasedAt", p.getReleasedAt() != null ? p.getReleasedAt().toString() : null);
            m.put("releasedBy", p.getReleasedBy());
        }
        return m;
    }

    private Map<String, Object> adjustmentToMap(CommissionAdjustment a) {
        Map<String, Object> m = new HashMap<>();
        m.put("id",             a.getId());
        m.put("periodId",       a.getPeriodId());
        m.put("agentId",        a.getAgentId());
        m.put("adjustmentType", a.getAdjustmentType());
        m.put("amount",         a.getAmount());
        m.put("reason",         a.getReason());
        m.put("createdAt",      a.getCreatedAt() != null ? a.getCreatedAt().toString() : null);
        m.put("createdBy",      a.getCreatedBy());
        return m;
    }

    // ── GET /api/commissions/periods/{id}/agents/{agentId}/statement/export ───

    @GetMapping("/periods/{id}/agents/{agentId}/statement/export")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> exportAgentStatement(
            @PathVariable Long id,
            @PathVariable Long agentId,
            @RequestParam(required = false, defaultValue = "pdf") String format) {

        CommissionPeriod period = periodRepository.findById(id).orElse(null);
        if (period == null)
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("Period not found".getBytes(StandardCharsets.UTF_8));

        Agent agent = agentRepository.findById(agentId).orElse(null);
        if (agent == null)
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("Agent not found".getBytes(StandardCharsets.UTF_8));

        List<CommissionEntry> entries = entryRepository.findByPeriodIdAndAgentId(id, agentId);
        BigDecimal totalOp = BigDecimal.ZERO;
        for (CommissionEntry e : entries) {
            if (e.getOpAmount() != null) totalOp = totalOp.add(e.getOpAmount());
        }

        List<CommissionAdjustment> adjustments = adjustmentRepository.findByPeriodIdAndAgentId(id, agentId);
        BigDecimal bonusSum     = BigDecimal.ZERO;
        BigDecimal deductionSum = BigDecimal.ZERO;
        for (CommissionAdjustment a : adjustments) {
            if ("BONUS".equals(a.getAdjustmentType()))         bonusSum     = bonusSum.add(a.getAmount());
            else if ("DEDUCTION".equals(a.getAdjustmentType())) deductionSum = deductionSum.add(a.getAmount());
        }
        BigDecimal totalAdjustments = bonusSum.subtract(deductionSum);
        BigDecimal netCommission    = totalOp.add(totalAdjustments);

        byte[] content;
        String contentType;
        String fileName;

        if ("csv".equalsIgnoreCase(format)) {
            content     = stmtCsvContent(entries, totalOp, totalAdjustments, netCommission)
                            .getBytes(StandardCharsets.UTF_8);
            contentType = "text/csv; charset=UTF-8";
            fileName    = "statement-" + agent.getAgentCode() + "-" + period.getPeriodCode() + ".csv";
        } else if ("excel".equalsIgnoreCase(format)) {
            content     = stmtExcelHtml(agent, period, entries, totalOp, totalAdjustments, netCommission)
                            .getBytes(StandardCharsets.UTF_8);
            contentType = "application/vnd.ms-excel";
            fileName    = "statement-" + agent.getAgentCode() + "-" + period.getPeriodCode() + ".xls";
        } else { // pdf (default)
            content     = stmtPdfHtml(agent, period, entries, adjustments, totalOp, totalAdjustments, netCommission)
                            .getBytes(StandardCharsets.UTF_8);
            contentType = "text/html; charset=UTF-8";
            fileName    = null;
        }

        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, contentType);
        if (fileName != null)
            builder = builder.header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + fileName + "\"");
        return builder.body(content);
    }

    // ── Statement export helpers ───────────────────────────────────────────────

    private String stmtPdfHtml(Agent agent, CommissionPeriod period,
                                List<CommissionEntry> entries,
                                List<CommissionAdjustment> adjustments,
                                BigDecimal totalOp, BigDecimal totalAdjustments,
                                BigDecimal netCommission) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\">");
        sb.append("<title>Commission Statement ").append(shEsc(agent.getAgentCode()))
          .append(" ").append(shEsc(period.getPeriodCode())).append("</title><style>");
        sb.append("body{font-family:Arial,sans-serif;font-size:12px;padding:20px;color:#1A1208;max-width:960px;margin:0 auto;}");
        sb.append(".hdr{display:flex;align-items:center;gap:14px;border-bottom:3px solid #E0A800;padding-bottom:12px;margin-bottom:16px;}");
        sb.append(".logo{width:40px;height:40px;background:#E0A800;border-radius:6px;display:flex;align-items:center;justify-content:center;font-weight:900;font-size:18px;color:#5C1A0E;flex-shrink:0;}");
        sb.append(".igrid{display:grid;grid-template-columns:1fr 1fr;gap:12px;margin-bottom:16px;}");
        sb.append(".ibox{background:#FFFBE6;border:1px solid #E0A800;border-radius:6px;padding:10px 14px;}");
        sb.append(".lbl{font-size:10px;color:#666;text-transform:uppercase;margin-bottom:2px;}");
        sb.append("h3{margin:16px 0 8px;font-size:13px;color:#5C1A0E;border-bottom:2px solid #E0A800;padding-bottom:4px;}");
        sb.append("table{width:100%;border-collapse:collapse;margin-bottom:16px;}");
        sb.append("th{background:#E0A800;padding:6px 8px;text-align:left;font-size:11px;text-transform:uppercase;}");
        sb.append("td{padding:5px 8px;border-bottom:1px solid #eee;font-size:11px;}");
        sb.append(".sbox{background:#FFFBE6;border:2px solid #E0A800;border-radius:6px;padding:14px;max-width:320px;margin-left:auto;}");
        sb.append(".srow{display:flex;justify-content:space-between;margin-bottom:6px;font-size:12px;}");
        sb.append(".stot{font-size:14px;font-weight:700;border-top:1px solid #E0A800;padding-top:8px;margin-top:4px;}");
        sb.append(".footer{margin-top:24px;font-size:10px;color:#999;text-align:center;border-top:1px solid #eee;padding-top:8px;}");
        sb.append("@media print{body{padding:10px;}}");
        sb.append("</style></head><body>");

        String logoUri = logoDataUri();
        if (!logoUri.isEmpty()) {
            sb.append("<div class=\"hdr\"><img src=\"").append(logoUri)
              .append("\" alt=\"RBM Packaging Supplies\" style=\"height:44px;width:auto;\">");
        } else {
            sb.append("<div class=\"hdr\"><div class=\"logo\">R</div>");
        }
        sb.append("<div><div style=\"font-size:15px;font-weight:700;color:#5C1A0E;\">RRBM Packaging Supplies and Trading</div>");
        sb.append("<div style=\"font-size:11px;color:#666;\">Commission Statement</div></div></div>");

        sb.append("<div class=\"igrid\">");
        sb.append("<div class=\"ibox\"><div class=\"lbl\">Agent</div>");
        sb.append("<div style=\"font-weight:700;\">").append(shEsc(agent.getAgentCode())).append("</div>");
        sb.append("<div style=\"font-size:12px;margin-top:2px;\">").append(shEsc(agent.getFullName())).append("</div>");
        sb.append("<div style=\"font-size:11px;color:#666;margin-top:2px;\">").append(shEsc(agent.getContactNumber())).append("</div></div>");
        sb.append("<div class=\"ibox\"><div class=\"lbl\">Period</div>");
        sb.append("<div style=\"font-weight:700;\">").append(shEsc(period.getPeriodCode())).append("</div>");
        sb.append("<div style=\"font-size:11px;color:#666;margin-top:2px;\">")
          .append(shEsc(period.getStartDate().toString())).append(" &ndash; ")
          .append(shEsc(period.getEndDate().toString())).append("</div>");
        sb.append("<div style=\"margin-top:4px;\"><span style=\"font-size:10px;font-weight:600;padding:1px 8px;border-radius:10px;background:#E0A800;color:#5C1A0E;\">")
          .append(shEsc(period.getStatus())).append("</span></div></div></div>");

        // Customer name per order — looked up live (CommissionEntry stores only orderId).
        // Batched into a single query; adjustment lines with no order resolve to blank.
        java.util.Map<String, String> customerByOrder = new java.util.HashMap<>();
        for (CommissionEntry e : entries)
            if (e.getOrderId() != null) customerByOrder.put(e.getOrderId(), "");
        for (Order o : orderRepository.findAllById(customerByOrder.keySet()))
            customerByOrder.put(o.getId(), o.getCustomerName() != null ? o.getCustomerName() : "");

        sb.append("<h3>Commission Entries</h3>");
        if (entries.isEmpty()) {
            sb.append("<p style=\"font-size:11px;color:#999;\">No entries for this agent in this period.</p>");
        } else {
            sb.append("<table><thead><tr>");
            for (String col : new String[]{"Order ID", "Customer", "Date", "Product", "Qty",
                                           "Base Price", "Rate", "O.P. Amount", "Status"})
                sb.append("<th>").append(col).append("</th>");
            sb.append("</tr></thead><tbody>");
            for (CommissionEntry e : entries) {
                sb.append("<tr>");
                stmtTd(sb, e.getOrderId());
                stmtTd(sb, e.getOrderId() != null ? customerByOrder.getOrDefault(e.getOrderId(), "") : "");
                stmtTd(sb, e.getOrderDate() != null ? e.getOrderDate().toString() : "");
                stmtTd(sb, e.getProductName());
                stmtTd(sb, String.valueOf(e.getQuantity()));
                stmtTd(sb, e.getBasePrice()  != null ? e.getBasePrice().toPlainString()  : "");
                stmtTd(sb, e.getOpRate()     != null ? e.getOpRate().toPlainString()      : "");
                stmtTd(sb, e.getOpAmount()   != null ? e.getOpAmount().toPlainString()    : "0.00");
                stmtTd(sb, e.getStatus());
                sb.append("</tr>");
            }
            sb.append("</tbody></table>");
        }

        if (!adjustments.isEmpty()) {
            sb.append("<h3>Adjustments</h3>");
            sb.append("<table><thead><tr>");
            for (String col : new String[]{"Type", "Amount", "Reason"})
                sb.append("<th>").append(col).append("</th>");
            sb.append("</tr></thead><tbody>");
            for (CommissionAdjustment adj : adjustments) {
                sb.append("<tr>");
                stmtTd(sb, adj.getAdjustmentType());
                stmtTd(sb, adj.getAmount() != null ? adj.getAmount().toPlainString() : "0.00");
                stmtTd(sb, adj.getReason());
                sb.append("</tr>");
            }
            sb.append("</tbody></table>");
        }

        sb.append("<div class=\"sbox\">");
        sb.append("<div class=\"srow\"><span>Total O.P.</span><span>")
          .append(totalOp.toPlainString()).append("</span></div>");
        sb.append("<div class=\"srow\"><span>Total Adjustments</span><span>")
          .append(totalAdjustments.toPlainString()).append("</span></div>");
        sb.append("<div class=\"srow stot\"><span>Net Commission</span><span>")
          .append(netCommission.toPlainString()).append("</span></div>");
        sb.append("</div>");

        sb.append("<div class=\"footer\">RRBM Management System &middot; Confidential &middot; Internal use only</div>");
        sb.append("<script>window.onload=function(){window.print();};<\\/script>");
        sb.append("</body></html>");
        return sb.toString();
    }

    private String stmtCsvContent(List<CommissionEntry> entries,
                                   BigDecimal totalOp, BigDecimal totalAdjustments,
                                   BigDecimal netCommission) {
        StringBuilder sb = new StringBuilder();
        sb.append("orderId,orderDate,productName,quantity,basePrice,opRate,opPerUnit,opAmount,status\n");
        for (CommissionEntry e : entries) {
            sb.append(stmtCsv(e.getOrderId())).append(",");
            sb.append(stmtCsv(e.getOrderDate() != null ? e.getOrderDate().toString() : "")).append(",");
            sb.append(stmtCsv(e.getProductName())).append(",");
            sb.append(stmtCsv(String.valueOf(e.getQuantity()))).append(",");
            sb.append(stmtCsv(e.getBasePrice()  != null ? e.getBasePrice().toPlainString()  : "")).append(",");
            sb.append(stmtCsv(e.getOpRate()     != null ? e.getOpRate().toPlainString()      : "")).append(",");
            sb.append(stmtCsv(e.getOpPerUnit()  != null ? e.getOpPerUnit().toPlainString()   : "")).append(",");
            sb.append(stmtCsv(e.getOpAmount()   != null ? e.getOpAmount().toPlainString()    : "0.00")).append(",");
            sb.append(stmtCsv(e.getStatus())).append("\n");
        }
        sb.append("\nSUMMARY\ntotalOp,totalAdjustments,netCommission\n");
        sb.append(totalOp.toPlainString()).append(",")
          .append(totalAdjustments.toPlainString()).append(",")
          .append(netCommission.toPlainString()).append("\n");
        return sb.toString();
    }

    private String stmtExcelHtml(Agent agent, CommissionPeriod period,
                                  List<CommissionEntry> entries,
                                  BigDecimal totalOp, BigDecimal totalAdjustments,
                                  BigDecimal netCommission) {
        StringBuilder sb = new StringBuilder();
        sb.append("<html><head><meta charset=\"UTF-8\">");
        sb.append("<style>table{border-collapse:collapse;}th,td{border:1px solid #999;padding:4px 8px;font-size:12px;}th{background:#E0A800;}</style>");
        sb.append("</head><body>");
        sb.append("<h3 style=\"font-family:Arial;font-size:13px;\">Commission Statement &mdash; ")
          .append(shEsc(agent.getAgentCode())).append(" &mdash; ")
          .append(shEsc(period.getPeriodCode())).append("</h3>");
        sb.append("<p style=\"font-family:Arial;font-size:11px;\">")
          .append(shEsc(agent.getFullName())).append(" | ")
          .append(shEsc(period.getStartDate().toString())).append(" to ")
          .append(shEsc(period.getEndDate().toString())).append("</p>");
        sb.append("<table><thead><tr>");
        for (String col : new String[]{"Order ID", "Date", "Product", "Qty",
                                       "Base Price", "Rate", "O.P. Amount", "Status"})
            sb.append("<th>").append(col).append("</th>");
        sb.append("</tr></thead><tbody>");
        for (CommissionEntry e : entries) {
            sb.append("<tr>");
            sb.append("<td>").append(shEsc(e.getOrderId())).append("</td>");
            sb.append("<td>").append(shEsc(e.getOrderDate() != null ? e.getOrderDate().toString() : "")).append("</td>");
            sb.append("<td>").append(shEsc(e.getProductName())).append("</td>");
            sb.append("<td>").append(e.getQuantity()).append("</td>");
            sb.append("<td>").append(e.getBasePrice()  != null ? e.getBasePrice().toPlainString()  : "").append("</td>");
            sb.append("<td>").append(e.getOpRate()     != null ? e.getOpRate().toPlainString()      : "").append("</td>");
            sb.append("<td>").append(e.getOpAmount()   != null ? e.getOpAmount().toPlainString()    : "0.00").append("</td>");
            sb.append("<td>").append(shEsc(e.getStatus())).append("</td>");
            sb.append("</tr>");
        }
        sb.append("</tbody></table>");
        sb.append("<br/><table><thead><tr>");
        sb.append("<th>Total O.P.</th><th>Total Adjustments</th><th>Net Commission</th>");
        sb.append("</tr></thead><tbody><tr>");
        sb.append("<td>").append(totalOp.toPlainString()).append("</td>");
        sb.append("<td>").append(totalAdjustments.toPlainString()).append("</td>");
        sb.append("<td><strong>").append(netCommission.toPlainString()).append("</strong></td>");
        sb.append("</tr></tbody></table></body></html>");
        return sb.toString();
    }

    private static void stmtTd(StringBuilder sb, String value) {
        sb.append("<td>").append(shEsc(value)).append("</td>");
    }

    private static String stmtCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n"))
            return "\"" + value.replace("\"", "\"\"") + "\"";
        return value;
    }

    private static String shEsc(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;")
                    .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
