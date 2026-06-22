package rrbm_backend;

import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cash-on-hand page API.
 *   GET  /api/cash-flow                 → current balance + history
 *   POST /api/cash-flow/opening-balance → one-time starting drawer count (admin key)
 *   POST /api/cash-flow/add-cash        → e.g. bank withdrawal into the drawer (admin key)
 *   POST /api/cash-flow/adjustment      → manual +/- reconciliation / returned change (admin key)
 *   POST /api/cash-flow/deposit         → cash deposited to the bank (MASTER key)
 */
@RestController
@RequestMapping("/api/cash-flow")
public class CashFlowController {

    private final CashLedgerService  cashLedgerService;
    private final MasterKeyService   masterKeyService;
    private final JwtUtil            jwtUtil;
    private final UserRepository     userRepository;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public CashFlowController(CashLedgerService cashLedgerService,
                              MasterKeyService masterKeyService,
                              JwtUtil jwtUtil,
                              UserRepository userRepository) {
        this.cashLedgerService = cashLedgerService;
        this.masterKeyService  = masterKeyService;
        this.jwtUtil           = jwtUtil;
        this.userRepository    = userRepository;
    }

    // ── Read: balance + history ───────────────────────────────────────────────
    @GetMapping
    public ResponseEntity<?> getCashFlow(
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        List<CashLedgerEntry> entries = cashLedgerService.history(limit, offset);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (CashLedgerEntry e : entries) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",            e.getId());
            m.put("entryType",     e.getEntryType());
            m.put("amount",        e.getAmount());
            m.put("entryDate",     e.getEntryDate() != null ? e.getEntryDate().toString() : null);
            m.put("referenceType", e.getReferenceType());
            m.put("referenceId",   e.getReferenceId());
            m.put("note",          e.getNote());
            m.put("createdBy",     e.getCreatedByName());
            m.put("createdAt",     e.getCreatedAt() != null ? e.getCreatedAt().toString() : null);
            rows.add(m);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("cashOnHand", cashLedgerService.getCashOnHand());
        resp.put("ledgerEmpty", cashLedgerService.ledgerIsEmpty());
        resp.put("entries", rows);
        return ResponseEntity.ok(resp);
    }

    // ── Opening balance (one-time, admin key) ──────────────────────────────────
    @PostMapping("/opening-balance")
    public ResponseEntity<?> openingBalance(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Caller c = resolveCaller(authHeader);
        if (c.error != null) return c.error;
        ResponseEntity<?> keyErr = requireAdminKey(c.user, body);
        if (keyErr != null) return keyErr;
        if (!cashLedgerService.ledgerIsEmpty()) {
            return ResponseEntity.status(409).body(Map.of(
                    "message", "An opening balance can only be set once, before any cash activity."));
        }
        BigDecimal amount = parseAmount(body.get("amount"));
        if (amount == null || amount.signum() < 0) {
            return ResponseEntity.badRequest().body(Map.of("message", "A valid opening amount is required."));
        }
        cashLedgerService.recordOpeningBalance(amount, parseDate(body.get("date")),
                str(body.get("note")), c.user.getId(), c.user.getFullName());
        return ResponseEntity.ok(Map.of("cashOnHand", cashLedgerService.getCashOnHand()));
    }

    // ── Add cash (admin key) ────────────────────────────────────────────────────
    @PostMapping("/add-cash")
    public ResponseEntity<?> addCash(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Caller c = resolveCaller(authHeader);
        if (c.error != null) return c.error;
        ResponseEntity<?> keyErr = requireAdminKey(c.user, body);
        if (keyErr != null) return keyErr;
        BigDecimal amount = parseAmount(body.get("amount"));
        if (amount == null || amount.signum() <= 0) {
            return ResponseEntity.badRequest().body(Map.of("message", "A positive amount is required."));
        }
        cashLedgerService.addCash(amount, parseDate(body.get("date")),
                str(body.get("note")), c.user.getId(), c.user.getFullName());
        return ResponseEntity.ok(Map.of("cashOnHand", cashLedgerService.getCashOnHand()));
    }

    // ── Adjustment: +/- (admin key) ─────────────────────────────────────────────
    @PostMapping("/adjustment")
    public ResponseEntity<?> adjustment(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Caller c = resolveCaller(authHeader);
        if (c.error != null) return c.error;
        ResponseEntity<?> keyErr = requireAdminKey(c.user, body);
        if (keyErr != null) return keyErr;
        BigDecimal amount = parseAmount(body.get("amount"));
        if (amount == null || amount.signum() == 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "A non-zero adjustment amount is required (negative to deduct)."));
        }
        cashLedgerService.adjustment(amount, parseDate(body.get("date")),
                str(body.get("note")), c.user.getId(), c.user.getFullName());
        return ResponseEntity.ok(Map.of("cashOnHand", cashLedgerService.getCashOnHand()));
    }

    // ── Deposit to bank (MASTER key) ────────────────────────────────────────────
    @PostMapping("/deposit")
    public ResponseEntity<?> deposit(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Caller c = resolveCaller(authHeader);
        if (c.error != null) return c.error;
        String masterKey = str(body.get("masterKey"));
        if (masterKey.isEmpty() || !masterKeyService.validateMasterKey(masterKey)) {
            return ResponseEntity.status(403).body(Map.of("message", "Incorrect master key"));
        }
        BigDecimal amount = parseAmount(body.get("amount"));
        if (amount == null || amount.signum() <= 0) {
            return ResponseEntity.badRequest().body(Map.of("message", "A positive deposit amount is required."));
        }
        LocalDate date = parseDate(body.get("date"));
        if (date == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "A deposit date is required."));
        }
        cashLedgerService.deposit(amount, date, str(body.get("note")),
                c.user.getId(), c.user.getFullName());
        return ResponseEntity.ok(Map.of("cashOnHand", cashLedgerService.getCashOnHand()));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private static final class Caller {
        User user;
        ResponseEntity<?> error;
        Caller(User u) { this.user = u; }
        Caller(ResponseEntity<?> e) { this.error = e; }
    }

    private Caller resolveCaller(String authHeader) {
        Long userId = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            try { userId = jwtUtil.extractUserId(authHeader.substring(7)); } catch (Exception ignored) {}
        }
        if (userId == null) return new Caller(ResponseEntity.status(401).body(Map.of("message", "Unauthorized")));
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) return new Caller(ResponseEntity.status(401).body(Map.of("message", "Unauthorized")));
        return new Caller(user);
    }

    /** Returns an error response if the admin security key is missing/wrong, else null. */
    private ResponseEntity<?> requireAdminKey(User caller, Map<String, Object> body) {
        String providedKey = str(body.get("securityKey"));
        if (caller.getAdminSecurityKey() == null) {
            return ResponseEntity.status(403).body(Map.of(
                    "message", "No admin security key has been set for your account. Ask your Super Admin to assign one."));
        }
        if (providedKey.isEmpty() || !passwordEncoder.matches(providedKey, caller.getAdminSecurityKey())) {
            return ResponseEntity.status(403).body(Map.of("message", "Incorrect admin security key"));
        }
        return null;
    }

    private static String str(Object o) { return o != null ? o.toString().trim() : ""; }

    private static BigDecimal parseAmount(Object o) {
        if (o == null) return null;
        try { return new BigDecimal(o.toString().trim()); } catch (Exception e) { return null; }
    }

    private static LocalDate parseDate(Object o) {
        if (o == null || o.toString().isBlank()) return null;
        try { return LocalDate.parse(o.toString().trim()); } catch (Exception e) { return null; }
    }
}
