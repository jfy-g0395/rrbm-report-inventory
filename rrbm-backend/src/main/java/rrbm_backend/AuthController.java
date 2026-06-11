package rrbm_backend;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;
import rrbm_backend.dto.LoginRequest;
import rrbm_backend.dto.LoginResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final ActivityLogService activityLogService;
    private final MasterKeyService masterKeyService;
    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;
    private final LoginAttemptService loginAttemptService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AuthController(AuthService authService,
                          ActivityLogService activityLogService,
                          MasterKeyService masterKeyService,
                          JwtUtil jwtUtil,
                          UserRepository userRepository,
                          LoginAttemptService loginAttemptService) {
        this.authService = authService;
        this.activityLogService = activityLogService;
        this.masterKeyService = masterKeyService;
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
        this.loginAttemptService = loginAttemptService;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        String identifier = request.getEmail() != null ? request.getEmail() : "";

        // M-4.1: brute-force lockout — reject before touching the credential check.
        if (loginAttemptService.isBlocked(identifier)) {
            long secs = loginAttemptService.secondsUntilUnlock(identifier);
            return ResponseEntity
                    .status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(new ErrorResponse("Too many failed login attempts. Try again in "
                            + ((secs + 59) / 60) + " minute(s)."));
        }

        try {
            LoginResponse response = authService.login(request);
            loginAttemptService.recordSuccess(identifier);
            activityLogService.log(
                response.getUser().getId(),
                response.getUser().getFullName(),
                "LOGIN",
                "User logged in",
                "USER",
                String.valueOf(response.getUser().getId())
            );
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            loginAttemptService.recordFailure(identifier);
            return ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorResponse(e.getMessage()));
        }
    }

    /**
     * Verify an admin's own password (for COD resume confirmation).
     * POST /api/auth/verify-password
     * Body: { "email": "...", "password": "..." }
     */
    @PostMapping("/verify-password")
    public ResponseEntity<?> verifyPassword(@RequestBody Map<String, String> body) {
        String email    = body.get("email");
        String password = body.get("password");
        if (email == null || password == null || email.isBlank() || password.isBlank()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("Email and password are required"));
        }
        boolean valid = authService.verifyPassword(email, password);
        if (valid) return ResponseEntity.ok(Map.of("valid", true));
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse("Invalid password"));
    }

    /**
     * Change the system master key.
     * POST /api/auth/master-key
     * Body: { "currentKey": "...", "newKey": "..." }
     */
    @PostMapping("/master-key")
    public ResponseEntity<?> changeMasterKey(@RequestBody Map<String, String> body,
                                              @RequestHeader("Authorization") String authHeader) {
        String currentKey = body.get("currentKey");
        String newKey     = body.get("newKey");

        if (currentKey == null || currentKey.isBlank() || newKey == null || newKey.isBlank())
            return ResponseEntity.badRequest().body(new ErrorResponse("Current key and new key are required"));
        if (newKey.length() < 6)
            return ResponseEntity.badRequest().body(new ErrorResponse("New master key must be at least 6 characters"));

        if (!masterKeyService.validateMasterKey(currentKey))
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorResponse("Current master key is incorrect"));

        Long userId = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            try { userId = jwtUtil.extractUserId(authHeader.substring(7)); } catch (Exception ignored) {}
        }

        masterKeyService.rotateMasterKey(newKey, userId);
        activityLogService.log(userId, "Admin", "CHANGE_MASTER_KEY",
                "Master key rotated", "SETTINGS", "master_key");

        return ResponseEntity.ok(Map.of("message", "Master key updated successfully"));
    }

    // ---------------------------------------------------------------
    // GET /api/auth/master-keys — list active master keys (no hashes)
    // ---------------------------------------------------------------
    @GetMapping("/master-keys")
    public ResponseEntity<?> listMasterKeys(@RequestHeader("Authorization") String authHeader) {
        Long userId = extractUserId(authHeader);
        if (userId == null) return ResponseEntity.status(401).body(Map.of("message", "Unauthorized"));
        // Only SUPER_ADMIN and ADMINISTRATOR can view
        User caller = userRepository.findById(userId).orElse(null);
        if (caller == null || (!("SUPER_ADMIN".equals(caller.getRole())) && !("ADMINISTRATOR".equals(caller.getRole()))))
            return ResponseEntity.status(403).body(Map.of("message", "Admin access required"));

        List<Map<String, Object>> keys = masterKeyService.listActiveKeys().stream().map(mk -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", mk.getId());
            m.put("label", mk.getLabel());
            m.put("createdAt", mk.getCreatedAt() != null ? mk.getCreatedAt().toString() : null);
            return m;
        }).collect(Collectors.toList());
        return ResponseEntity.ok(keys);
    }

    // ---------------------------------------------------------------
    // POST /api/auth/master-keys — add a new master key (max 3)
    // ---------------------------------------------------------------
    @PostMapping("/master-keys")
    public ResponseEntity<?> addMasterKey(@RequestBody Map<String, String> body,
                                           @RequestHeader("Authorization") String authHeader) {
        Long userId = extractUserId(authHeader);
        if (userId == null) return ResponseEntity.status(401).body(Map.of("message", "Unauthorized"));
        User caller = userRepository.findById(userId).orElse(null);
        if (caller == null || !("SUPER_ADMIN".equals(caller.getRole())))
            return ResponseEntity.status(403).body(Map.of("message", "Super Admin access required"));

        String rawKey = body.getOrDefault("key", "").trim();
        String label  = body.getOrDefault("label", "").trim();
        if (rawKey.length() < 6)
            return ResponseEntity.badRequest().body(Map.of("message", "Master key must be at least 6 characters"));
        if (label.isBlank()) label = null;

        try {
            MasterKey mk = masterKeyService.addMasterKey(rawKey, label, userId);
            activityLogService.log(userId, caller.getFullName(), "ADD_MASTER_KEY",
                    "Added master key: " + (mk.getLabel() != null ? mk.getLabel() : "Key"), "SETTINGS", "master_key");
            return ResponseEntity.ok(Map.of("message", "Master key added", "id", mk.getId(), "label", mk.getLabel()));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    // ---------------------------------------------------------------
    // DELETE /api/auth/master-keys/{id} — deactivate a master key
    // ---------------------------------------------------------------
    @DeleteMapping("/master-keys/{id}")
    public ResponseEntity<?> removeMasterKey(@PathVariable Long id,
                                              @RequestHeader("Authorization") String authHeader) {
        Long userId = extractUserId(authHeader);
        if (userId == null) return ResponseEntity.status(401).body(Map.of("message", "Unauthorized"));
        User caller = userRepository.findById(userId).orElse(null);
        if (caller == null || !("SUPER_ADMIN".equals(caller.getRole())))
            return ResponseEntity.status(403).body(Map.of("message", "Super Admin access required"));

        try {
            masterKeyService.removeMasterKey(id);
            activityLogService.log(userId, caller.getFullName(), "REMOVE_MASTER_KEY",
                    "Deactivated master key #" + id, "SETTINGS", "master_key");
            return ResponseEntity.ok(Map.of("message", "Master key removed"));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    private Long extractUserId(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return null;
        try { return jwtUtil.extractUserId(authHeader.substring(7)); } catch (Exception e) { return null; }
    }

    /**
     * Verify the caller's personal admin security key.
     * POST /api/auth/verify-security-key
     * Body: { "securityKey": "..." }
     * Returns 200 {"valid":true} on match, 403 on mismatch or unassigned key.
     */
    @PostMapping("/verify-security-key")
    public ResponseEntity<?> verifySecurityKey(
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        String providedKey = body.getOrDefault("securityKey", "").trim();

        Long userId = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            try { userId = jwtUtil.extractUserId(authHeader.substring(7)); } catch (Exception ignored) {}
        }
        if (userId == null)
            return ResponseEntity.status(401).body(Map.of("message", "Unauthorized"));

        final Long finalUserId = userId;
        return userRepository.findById(finalUserId).map(u -> {
            if (u.getAdminSecurityKey() == null) {
                return ResponseEntity.status(403)
                    .body(Map.of("message", "No admin security key has been set for your account. Ask your Super Admin to assign one."));
            }
            if (passwordEncoder.matches(providedKey, u.getAdminSecurityKey())) {
                return ResponseEntity.ok(Map.of("valid", true));
            }
            return ResponseEntity.status(403)
                .body(Map.of("message", "Incorrect admin security key"));
        }).orElse(ResponseEntity.status(403).body(Map.of("message", "User not found")));
    }

    /**
     * Verify a key against ANY Super Admin / Administrator's personal security key.
     * Used for the force-close daily sales dual-auth flow.
     * POST /api/auth/verify-superadmin-key
     * Body: { "securityKey": "..." }
     * Returns 200 {"valid":true} if any super admin's key matches; 403 otherwise.
     */
    @PostMapping("/verify-superadmin-key")
    public ResponseEntity<?> verifySuperAdminKey(@RequestBody Map<String, String> body) {
        String provided = body.getOrDefault("securityKey", "").trim();
        if (provided.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "securityKey is required"));
        }
        boolean matched = userRepository.findAll().stream()
            .filter(u -> "SUPER_ADMIN".equals(u.getRole()) || "ADMINISTRATOR".equals(u.getRole()))
            .filter(u -> u.getAdminSecurityKey() != null)
            .anyMatch(u -> passwordEncoder.matches(provided, u.getAdminSecurityKey()));
        if (matched) return ResponseEntity.ok(Map.of("valid", true));
        return ResponseEntity.status(403).body(Map.of("message", "No Super Admin key matched"));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        // N-9: actor from JWT, not URL param — a client could previously log any userId.
        Long userId = null;
        String userName = "Unknown";
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            try {
                String token = authHeader.substring(7);
                userId = jwtUtil.extractUserId(token);
                if (userId != null) {
                    userName = userRepository.findById(userId)
                            .map(User::getFullName).orElse("Unknown");
                }
            } catch (Exception ignored) {}
        }
        activityLogService.log(userId, userName, "LOGOUT", "User logged out", "USER",
                userId != null ? String.valueOf(userId) : null);
        return ResponseEntity.ok().build();
    }

    record ErrorResponse(String message) {}
}
