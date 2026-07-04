package rrbm_backend;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * REST API for scheduled inter-warehouse stock moves.
 *
 *   POST   /api/stock-transfers            create (any authenticated user)
 *   GET    /api/stock-transfers?status=    list
 *   GET    /api/stock-transfers/{id}       detail
 *   POST   /api/stock-transfers/{id}/approve      approver-gated
 *   POST   /api/stock-transfers/{id}/reschedule   approver-gated
 *   POST   /api/stock-transfers/{id}/reject       approver-gated
 *   POST   /api/stock-transfers/{id}/complete     approver-gated (moves stock)
 *   POST   /api/stock-transfers/{id}/cancel       requester or approver
 *
 * Approvers = SUPER_ADMIN, ADMINISTRATOR, DELIVERY_MANAGEMENT.
 */
@RestController
@RequestMapping("/api/stock-transfers")
public class StockTransferController {

    /** Roles allowed to approve / reschedule / reject / complete a move. */
    private static final Set<String> APPROVER_ROLES =
            Set.of("SUPER_ADMIN", "ADMINISTRATOR", "DELIVERY_MANAGEMENT");

    private final StockTransferService transferService;
    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;

    public StockTransferController(StockTransferService transferService,
                                   JwtUtil jwtUtil,
                                   UserRepository userRepository) {
        this.transferService = transferService;
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
    }

    // ── request bodies ────────────────────────────────────────────────────────

    public record LineRequest(Long productId, String fromWarehouse, String toWarehouse, Integer quantity) {}
    public record CreateRequest(List<LineRequest> items, LocalDate scheduledDate, String notes) {}
    public record RescheduleRequest(LocalDate scheduledDate) {}
    public record RejectRequest(String reason) {}

    // ── endpoints ─────────────────────────────────────────────────────────────

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateRequest body,
                                    @RequestHeader("Authorization") String authHeader) {
        User user = userFromHeader(authHeader);
        if (user == null) return unauthorized();
        try {
            List<StockTransferService.LineSpec> lines = body.items() == null ? List.of()
                    : body.items().stream()
                        .map(l -> new StockTransferService.LineSpec(
                                l.productId(), l.fromWarehouse(), l.toWarehouse(), l.quantity()))
                        .collect(Collectors.toList());
            StockTransfer saved = transferService.create(
                    lines, body.scheduledDate(), body.notes(), user.getId(), user.getFullName());
            return ResponseEntity.status(HttpStatus.CREATED).body(saved);
        } catch (Exception e) {
            return badRequest(e);
        }
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(required = false) String status,
                                  @RequestHeader("Authorization") String authHeader) {
        if (userFromHeader(authHeader) == null) return unauthorized();
        return ResponseEntity.ok(transferService.list(status));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable Long id,
                                 @RequestHeader("Authorization") String authHeader) {
        if (userFromHeader(authHeader) == null) return unauthorized();
        try {
            return ResponseEntity.ok(transferService.get(id));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<?> approve(@PathVariable Long id,
                                     @RequestHeader("Authorization") String authHeader) {
        User user = userFromHeader(authHeader);
        if (user == null) return unauthorized();
        if (!isApprover(user)) return forbidden();
        try {
            return ResponseEntity.ok(transferService.approve(id, user.getId(), user.getFullName()));
        } catch (Exception e) { return badRequest(e); }
    }

    @PostMapping("/{id}/reschedule")
    public ResponseEntity<?> reschedule(@PathVariable Long id, @RequestBody RescheduleRequest body,
                                        @RequestHeader("Authorization") String authHeader) {
        User user = userFromHeader(authHeader);
        if (user == null) return unauthorized();
        if (!isApprover(user)) return forbidden();
        try {
            return ResponseEntity.ok(transferService.reschedule(
                    id, body != null ? body.scheduledDate() : null, user.getId(), user.getFullName()));
        } catch (Exception e) { return badRequest(e); }
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<?> reject(@PathVariable Long id, @RequestBody(required = false) RejectRequest body,
                                    @RequestHeader("Authorization") String authHeader) {
        User user = userFromHeader(authHeader);
        if (user == null) return unauthorized();
        if (!isApprover(user)) return forbidden();
        try {
            return ResponseEntity.ok(transferService.reject(
                    id, body != null ? body.reason() : null, user.getId(), user.getFullName()));
        } catch (Exception e) { return badRequest(e); }
    }

    @PostMapping("/{id}/complete")
    public ResponseEntity<?> complete(@PathVariable Long id,
                                      @RequestHeader("Authorization") String authHeader) {
        User user = userFromHeader(authHeader);
        if (user == null) return unauthorized();
        if (!isApprover(user)) return forbidden();
        try {
            return ResponseEntity.ok(transferService.complete(id, user.getId(), user.getFullName()));
        } catch (Exception e) { return badRequest(e); }
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<?> cancel(@PathVariable Long id,
                                    @RequestHeader("Authorization") String authHeader) {
        User user = userFromHeader(authHeader);
        if (user == null) return unauthorized();
        try {
            // Cancel is permitted to an approver or to the original requester.
            StockTransfer t = transferService.get(id);
            boolean isRequester = t.getRequestedBy() != null && t.getRequestedBy().equals(user.getId());
            if (!isApprover(user) && !isRequester) return forbidden();
            return ResponseEntity.ok(transferService.cancel(id, user.getId(), user.getFullName()));
        } catch (Exception e) { return badRequest(e); }
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private User userFromHeader(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return null;
        Long userId;
        try { userId = jwtUtil.extractUserId(authHeader.substring(7)); }
        catch (Exception e) { return null; }
        if (userId == null) return null;
        return userRepository.findById(userId).orElse(null);
    }

    private boolean isApprover(User user) {
        return user != null && user.getRole() != null && APPROVER_ROLES.contains(user.getRole());
    }

    private ResponseEntity<?> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("message", "Invalid or missing authentication token"));
    }

    private ResponseEntity<?> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("message", "Only Super Admin, Administrator, or Delivery Management can perform this action"));
    }

    private ResponseEntity<?> badRequest(Exception e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", e.getMessage()));
    }
}
