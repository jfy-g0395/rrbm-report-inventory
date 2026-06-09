package rrbm_backend;

import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
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
@CrossOrigin(origins = "*")
public class AgentController {

    private final AgentRepository           agentRepository;
    private final AgentCommissionRepository agentCommissionRepository;
    private final CommissionEntryRepository commissionEntryRepository;
    private final UserRepository            userRepository;
    private final ActivityLogService        activityLogService;
    private final JwtUtil                   jwtUtil;

    public AgentController(AgentRepository agentRepository,
                           AgentCommissionRepository agentCommissionRepository,
                           CommissionEntryRepository commissionEntryRepository,
                           UserRepository userRepository,
                           ActivityLogService activityLogService,
                           JwtUtil jwtUtil) {
        this.agentRepository           = agentRepository;
        this.agentCommissionRepository = agentCommissionRepository;
        this.commissionEntryRepository = commissionEntryRepository;
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

        // Build result maps — triggers lifetimeNetCommission N+1 per agent.
        List<Map<String, Object>> result = agents.stream()
                .map(a -> toMap(a, agentRepository.countOrdersByAgentId(a.getId())))
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

        List<AgentCommission> allCommissions = agentCommissionRepository.findByAgentId(id);

        // Lifetime net commission is always all-time regardless of filter.
        BigDecimal lifetimeNetCommission = allCommissions.stream()
                .map(AgentCommission::getNetCommission)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Apply optional period overlap filter for commissionSummary.
        List<AgentCommission> filtered;
        if (year != null && month != null) {
            LocalDate firstDay = LocalDate.of(year, month, 1);
            LocalDate lastDay  = firstDay.withDayOfMonth(firstDay.lengthOfMonth());
            filtered = allCommissions.stream()
                    .filter(ac -> !ac.getEndDate().isBefore(firstDay) && !ac.getStartDate().isAfter(lastDay))
                    .collect(Collectors.toList());
        } else {
            filtered = new ArrayList<>(allCommissions);
        }

        // Newest first (releasedAt DESC).
        filtered.sort((a, b) -> b.getReleasedAt().compareTo(a.getReleasedAt()));

        List<Map<String, Object>> commissionSummary = filtered.stream()
                .map(ac -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("periodId",       ac.getPeriodId());
                    row.put("periodCode",     ac.getPeriodCode());
                    row.put("startDate",      ac.getStartDate().toString());
                    row.put("endDate",        ac.getEndDate().toString());
                    row.put("totalOp",        ac.getTotalOp());
                    row.put("totalBonus",     ac.getTotalBonus());
                    row.put("totalDeduction", ac.getTotalDeduction());
                    row.put("netCommission",  ac.getNetCommission());
                    row.put("releasedAt",     ac.getReleasedAt().toString());
                    row.put("status",         ac.getStatus());
                    row.put("paymentMethod",  ac.getPaymentMethod());
                    row.put("paymentReference", ac.getPaymentReference());
                    row.put("paymentDate",    ac.getPaymentDate() != null ? ac.getPaymentDate().toString() : null);
                    row.put("paidAt",         ac.getPaidAt() != null ? ac.getPaidAt().toString() : null);
                    return row;
                })
                .collect(Collectors.toList());

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
        return agentCommissionRepository.findByAgentId(agentId).stream()
                .map(AgentCommission::getNetCommission)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Map<String, Object> toMap(Agent agent, long totalOrders) {
        return toMap(agent, totalOrders, lifetimeNetCommission(agent.getId()));
    }

    private Map<String, Object> toMap(Agent agent, long totalOrders, BigDecimal lifetimeNet) {
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
        m.put("pendingCommission",      commissionEntryRepository.sumPendingOpAmountByAgentId(agent.getId()));
        return m;
    }
}
