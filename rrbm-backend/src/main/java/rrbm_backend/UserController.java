package rrbm_backend;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.HashMap;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserRepository userRepository;
    private final ActivityLogService activityLogService;
    private final JwtUtil jwtUtil;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private static final List<String> VALID_ROLES =
        List.of("SUPER_ADMIN", "ADMIN", "ADMINISTRATOR", "ACCOUNTING", "STANDARD_USER");

    private static final String ALL_PAGES =
        "[\"dashboard\",\"orders\",\"order-history\",\"daily-reports\",\"inventory\",\"purchase-orders\",\"receive-stocks\",\"rejected-items\",\"reports\",\"delivery-reports\",\"activity-log\",\"employees\",\"expenses\",\"payables\",\"suppliers\",\"collections\",\"ledger\",\"agents\",\"import\",\"cash-flow\"]";

    private static final Map<String, String> ROLE_DEFAULT_PAGES;
    static {
        Map<String, String> m = new HashMap<>();
        m.put("STANDARD_USER",
            "[\"orders\",\"rejected-items\",\"receive-stocks\",\"inventory\",\"delivery-reports\"]");
        m.put("ACCOUNTING",
            "[\"dashboard\",\"orders\",\"daily-reports\",\"inventory\",\"purchase-orders\",\"receive-stocks\",\"rejected-items\",\"reports\",\"expenses\",\"payables\",\"suppliers\",\"collections\",\"ledger\",\"agents\",\"import\"]");
        m.put("ADMINISTRATOR", ALL_PAGES);
        m.put("ADMIN",         ALL_PAGES);
        // SUPER_ADMIN → null (bypass; stored value is irrelevant)
        ROLE_DEFAULT_PAGES = java.util.Collections.unmodifiableMap(m);
    }

    public UserController(UserRepository userRepository,
                          ActivityLogService activityLogService,
                          JwtUtil jwtUtil) {
        this.userRepository     = userRepository;
        this.activityLogService = activityLogService;
        this.jwtUtil            = jwtUtil;
    }

    private Long userIdFromHeader(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return null;
        return jwtUtil.extractUserId(authHeader.substring(7));
    }

    /** Resolve the calling User from the Authorization header, or null if absent/invalid. */
    private User callerFromHeader(String authHeader) {
        Long callerId = userIdFromHeader(authHeader);
        return callerId != null ? userRepository.findById(callerId).orElse(null) : null;
    }

    /** Account management (create/edit) is limited to Super Admin and Administrator. */
    private static boolean isManager(User u) {
        return u != null && ("SUPER_ADMIN".equals(u.getRole()) || "ADMINISTRATOR".equals(u.getRole()));
    }

    // ---------------------------------------------------------------
    // GET /api/users — list all users
    // ---------------------------------------------------------------
    @GetMapping
    public ResponseEntity<?> listUsers(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        // M-2.1: full staff list (incl. PII) is limited to account managers.
        if (!isManager(callerFromHeader(authHeader)))
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "Only Super Admin or Administrator can list users"));
        return ResponseEntity.ok(
            userRepository.findAll().stream().map(this::toDto).collect(Collectors.toList())
        );
    }

    // ---------------------------------------------------------------
    // GET /api/users/{id} — single user (managers, or the user themselves)
    // ---------------------------------------------------------------
    @GetMapping("/{id}")
    public ResponseEntity<?> getUser(@PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        // M-2.1: a caller may read their own record; otherwise manager-only.
        User caller = callerFromHeader(authHeader);
        if (caller == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Unauthorized"));
        if (!isManager(caller) && !caller.getId().equals(id))
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "You can only view your own profile"));
        return userRepository.findById(id)
            .<ResponseEntity<?>>map(u -> ResponseEntity.ok(toDto(u)))
            .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "User not found")));
    }

    // ---------------------------------------------------------------
    // POST /api/users — create employee account
    // Body keys: fullName, employeeId, email, username, password, role,
    //            designation, birthdate (YYYY-MM-DD), address, contactNumber,
    //            profileImage (base64 dataURL), createdByName
    // ---------------------------------------------------------------
    @PostMapping
    public ResponseEntity<?> createUser(
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Long userId          = userIdFromHeader(authHeader);

        // M-1.1: only Super Admin / Administrator may create accounts.
        User caller = callerFromHeader(authHeader);
        if (!isManager(caller))
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "Only Super Admin or Administrator can create accounts"));

        String fullName      = trim(body.get("fullName"));
        String email         = trim(body.get("email"));
        String password      = body.get("password");
        String role          = body.getOrDefault("role", "STANDARD_USER").trim();
        String createdByName = body.getOrDefault("createdByName", "Admin");

        if (fullName == null || fullName.isBlank())
            return bad("Employee name is required");
        if (email == null || email.isBlank())
            return bad("Email is required");
        if (password == null || password.length() < 6)
            return bad("Password must be at least 6 characters");
        if (!VALID_ROLES.contains(role))
            return bad("Invalid role: " + role);
        // M-1.1: an Administrator may not mint a Super Admin (privilege escalation).
        if (!"SUPER_ADMIN".equals(caller.getRole()) && "SUPER_ADMIN".equals(role))
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "Only Super Admin can assign the Super Admin role"));
        if (userRepository.existsByEmail(email.toLowerCase()))
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "Email is already registered"));

        String empId    = trim(body.get("employeeId"));
        String username = trim(body.get("username"));

        if (empId != null && userRepository.existsByEmployeeId(empId))
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "Employee ID is already in use"));
        if (username != null && userRepository.existsByUsername(username))
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "Username is already taken"));

        User user = new User();
        applyProfile(user, body);
        user.setEmail(email.toLowerCase());
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setRole(role);
        user.setStatus("ACTIVE");
        // Default: force password change on first login unless explicitly opted out
        user.setMustChangePassword(!"false".equals(body.get("mustChangePassword")));

        // Page access: Super Admin may pass a custom list or fall back to role default;
        // non-Super-Admin callers always receive the role default (body value ignored).
        String allowedPages;
        if ("SUPER_ADMIN".equals(caller.getRole())) {
            String bodyPages = body.get("allowedPages");
            allowedPages = (bodyPages != null && !bodyPages.isBlank())
                ? bodyPages
                : ROLE_DEFAULT_PAGES.getOrDefault(role, ALL_PAGES);
        } else {
            allowedPages = ROLE_DEFAULT_PAGES.getOrDefault(role, ALL_PAGES);
        }
        if (allowedPages == null) allowedPages = ALL_PAGES;
        user.setAllowedPages(allowedPages);

        User saved = userRepository.save(user);
        activityLogService.log(userId, createdByName, "CREATE_USER",
            "Created employee account for " + saved.getFullName()
            + " (" + saved.getEmail() + ") — Role: " + role,
            "USER", String.valueOf(saved.getId()));

        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(saved));
    }

    // ---------------------------------------------------------------
    // PUT /api/users/{id} — full employee profile edit
    // Same body keys as POST; password is optional (blank = keep existing)
    // ---------------------------------------------------------------
    @PutMapping("/{id}")
    public ResponseEntity<?> updateUser(
            @PathVariable Long id,
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Long userId          = userIdFromHeader(authHeader);
        String changedByName = body.getOrDefault("changedByName", "Admin");

        // M-1.1: only Super Admin / Administrator may edit accounts.
        User caller = callerFromHeader(authHeader);
        if (!isManager(caller))
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "Only Super Admin or Administrator can edit accounts"));
        boolean callerIsSuper = "SUPER_ADMIN".equals(caller.getRole());

        return userRepository.findById(id).map(user -> {
            // M-1.1: an Administrator may not edit a Super Admin account
            // (blocks password reset / demotion of a Super Admin by a lower role).
            if (!callerIsSuper && "SUPER_ADMIN".equals(user.getRole()))
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("message", "Only Super Admin can edit a Super Admin account"));

            // Email uniqueness check (if changing)
            String newEmail = trim(body.get("email"));
            if (newEmail != null && !newEmail.equalsIgnoreCase(user.getEmail())) {
                if (userRepository.existsByEmail(newEmail.toLowerCase()))
                    return bad("Email is already registered");
                user.setEmail(newEmail.toLowerCase());
            }

            // Employee ID uniqueness check
            String newEmpId = trim(body.get("employeeId"));
            if (newEmpId != null && !newEmpId.equals(user.getEmployeeId())) {
                if (userRepository.existsByEmployeeId(newEmpId))
                    return bad("Employee ID is already in use");
            }

            // Username uniqueness check
            String newUsername = trim(body.get("username"));
            if (newUsername != null && !newUsername.equals(user.getUsername())) {
                if (userRepository.existsByUsername(newUsername))
                    return bad("Username is already taken");
            }

            applyProfile(user, body);

            // Role update (if provided)
            String newRole = trim(body.get("role"));
            if (newRole != null && !newRole.isBlank()) {
                if (!VALID_ROLES.contains(newRole))
                    return bad("Invalid role: " + newRole);
                // M-1.1: an Administrator may not promote anyone to Super Admin.
                if (!callerIsSuper && "SUPER_ADMIN".equals(newRole))
                    return ResponseEntity.status(HttpStatus.FORBIDDEN)
                            .body(Map.of("message", "Only Super Admin can assign the Super Admin role"));
                if (!newRole.equals(user.getRole()) && !callerIsSuper) {
                    String roleDefault = ROLE_DEFAULT_PAGES.getOrDefault(newRole, ALL_PAGES);
                    if (roleDefault == null) roleDefault = ALL_PAGES;
                    user.setAllowedPages(roleDefault);
                }
                user.setRole(newRole);
            }

            // Password update (optional — blank means keep current)
            String newPassword = body.get("password");
            if (newPassword != null && !newPassword.isBlank()) {
                if (newPassword.length() < 6) return bad("Password must be at least 6 characters");
                user.setPasswordHash(passwordEncoder.encode(newPassword));
                // Default: force password change unless Super Admin explicitly opted out
                user.setMustChangePassword(!"false".equals(body.get("mustChangePassword")));
            }

            User saved = userRepository.save(user);
            activityLogService.log(userId, changedByName, "UPDATE_USER",
                "Updated profile for " + saved.getFullName() + " (" + saved.getEmail() + ")",
                "USER", String.valueOf(id));

            return ResponseEntity.ok(toDto(saved));
        }).orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "User not found")));
    }

    // ---------------------------------------------------------------
    // PATCH /api/users/{id}/role
    // ---------------------------------------------------------------
    @PatchMapping("/{id}/role")
    public ResponseEntity<?> updateRole(@PathVariable Long id,
                                        @RequestBody Map<String, String> body,
                                        @RequestHeader(value = "Authorization", required = false) String authHeader) {
        // M-32: SUPER_ADMIN gate — previously any authenticated user could self-promote.
        Long callerId = userIdFromHeader(authHeader);
        if (callerId == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Unauthorized"));
        User caller = userRepository.findById(callerId).orElse(null);
        if (caller == null || !"SUPER_ADMIN".equals(caller.getRole()))
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "Only Super Admin can change user roles"));

        String newRole = body.get("role");
        if (newRole == null || !VALID_ROLES.contains(newRole))
            return bad("Invalid role");

        return userRepository.findById(id).map(user -> {
            String oldRole = user.getRole();
            user.setRole(newRole);
            String newDefaultPages = ROLE_DEFAULT_PAGES.get(newRole);
            user.setAllowedPages(newDefaultPages != null ? newDefaultPages : ALL_PAGES);
            userRepository.save(user);
            activityLogService.log(callerId, caller.getFullName(), "UPDATE_USER_ROLE",
                "Changed role for " + user.getFullName() + " from " + oldRole + " to " + newRole,
                "USER", String.valueOf(id));
            return ResponseEntity.ok(Map.of("message", "Role updated", "userId", id, "role", newRole));
        }).orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "User not found")));
    }

    // ---------------------------------------------------------------
    // PATCH /api/users/{id}/status
    // ---------------------------------------------------------------
    @PatchMapping("/{id}/status")
    public ResponseEntity<?> updateStatus(@PathVariable Long id,
                                          @RequestBody Map<String, String> body,
                                          @RequestHeader(value = "Authorization", required = false) String authHeader) {
        // M-32: SUPER_ADMIN gate — previously any authenticated user could disable any account.
        Long callerId = userIdFromHeader(authHeader);
        if (callerId == null)
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", "Unauthorized"));
        User caller = userRepository.findById(callerId).orElse(null);
        if (caller == null || !"SUPER_ADMIN".equals(caller.getRole()))
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "Only Super Admin can change user status"));

        String newStatus = body.get("status");
        if (newStatus == null || !List.of("ACTIVE", "AWAY", "DISABLED").contains(newStatus))
            return bad("Invalid status");

        return userRepository.findById(id).map(user -> {
            user.setStatus(newStatus);
            userRepository.save(user);
            activityLogService.log(callerId, caller.getFullName(), "UPDATE_USER_STATUS",
                "Set status for " + user.getFullName() + " to " + newStatus,
                "USER", String.valueOf(id));
            return ResponseEntity.ok(Map.of("message", "Status updated", "userId", id, "status", newStatus));
        }).orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "User not found")));
    }

    // ---------------------------------------------------------------
    // PATCH /api/users/{id}/security-key — Super Admin only
    // Sets or changes the admin security key for an employee.
    // Body: { "securityKey": "..." }
    // ---------------------------------------------------------------
    @PatchMapping("/{id}/security-key")
    public ResponseEntity<?> setSecurityKey(
            @PathVariable Long id,
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        Long callerId = userIdFromHeader(authHeader);
        if (callerId == null) return ResponseEntity.status(401).body(Map.of("message", "Unauthorized"));

        User caller = userRepository.findById(callerId).orElse(null);
        if (caller == null || !"SUPER_ADMIN".equals(caller.getRole()))
            return ResponseEntity.status(403).body(Map.of("message", "Only Super Admin can assign security keys"));

        String newKey = body.getOrDefault("securityKey", "").trim();
        if (newKey.length() < 6)
            return ResponseEntity.badRequest().body(Map.of("message", "Security key must be at least 6 characters"));

        return userRepository.findById(id).<ResponseEntity<?>>map(u -> {
            u.setAdminSecurityKey(passwordEncoder.encode(newKey));
            userRepository.save(u);
            activityLogService.log(callerId, caller.getFullName(), "ASSIGN_SECURITY_KEY",
                "Admin security key assigned/changed for: " + u.getFullName(),
                "USER", String.valueOf(id));
            return ResponseEntity.ok(Map.of("message", "Security key updated for " + u.getFullName()));
        }).orElse(ResponseEntity.status(404).body(Map.of("message", "User not found")));
    }

    // ---------------------------------------------------------------
    // PATCH /api/users/{id}/permissions — Super Admin only
    // Body: { "allowedPages": "[\"orders\",\"inventory\",...]", "changedByName": "..." }
    // ---------------------------------------------------------------
    @PatchMapping("/{id}/permissions")
    public ResponseEntity<?> updatePermissions(
            @PathVariable Long id,
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        // Enforce Super Admin only
        Long callerId = userIdFromHeader(authHeader);
        if (callerId == null) return ResponseEntity.status(401).body(Map.of("message", "Unauthorized"));
        User caller = userRepository.findById(callerId).orElse(null);
        if (caller == null || !"SUPER_ADMIN".equals(caller.getRole()))
            return ResponseEntity.status(403).body(Map.of("message", "Only Super Admin can update page permissions"));

        String newPages      = body.get("allowedPages");
        String changedByName = body.getOrDefault("changedByName", caller.getFullName());

        if (newPages == null)
            return ResponseEntity.badRequest().body(Map.of("message", "allowedPages is required"));

        return userRepository.findById(id).<ResponseEntity<?>>map(user -> {
            user.setAllowedPages(newPages);
            User saved = userRepository.save(user);
            activityLogService.log(callerId, changedByName, "UPDATE_USER_PERMISSIONS",
                "Updated page permissions for " + user.getFullName(),
                "USER", String.valueOf(id));
            return ResponseEntity.ok(toDto(saved));
        }).orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "User not found")));
    }

    // ---------------------------------------------------------------
    // DELETE /api/users/{id} — Super Admin only, security key required
    // Guards: cannot delete self; cannot delete last Super Admin;
    //         FK-linked accounts return 409 (suggest Disable instead)
    // ---------------------------------------------------------------
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteUser(
            @PathVariable Long id,
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        Long callerId = userIdFromHeader(authHeader);
        if (callerId == null) return ResponseEntity.status(401).body(Map.of("message", "Unauthorized"));

        User caller = userRepository.findById(callerId).orElse(null);
        if (caller == null || !"SUPER_ADMIN".equals(caller.getRole()))
            return ResponseEntity.status(403).body(Map.of("message", "Only Super Admin can delete accounts"));

        if (callerId.equals(id))
            return ResponseEntity.badRequest().body(Map.of("message", "You cannot delete your own account"));

        // Verify caller's admin security key
        String securityKey = body.getOrDefault("securityKey", "").trim();
        if (caller.getAdminSecurityKey() == null || caller.getAdminSecurityKey().isBlank())
            return ResponseEntity.status(403).body(Map.of("message", "Set your admin security key before deleting accounts"));
        if (!passwordEncoder.matches(securityKey, caller.getAdminSecurityKey()))
            return ResponseEntity.status(401).body(Map.of("message", "Incorrect security key"));

        return userRepository.findById(id).<ResponseEntity<?>>map(target -> {
            // Guard: cannot remove the only remaining Super Admin
            if ("SUPER_ADMIN".equals(target.getRole())) {
                long superCount = userRepository.findAll().stream()
                    .filter(u -> "SUPER_ADMIN".equals(u.getRole())).count();
                if (superCount <= 1)
                    return ResponseEntity.badRequest()
                        .body(Map.of("message", "Cannot delete the only Super Admin account"));
            }
            String deletedName  = target.getFullName();
            String deletedEmail = target.getEmail();
            try {
                userRepository.delete(target);
                activityLogService.log(callerId, caller.getFullName(), "DELETE_USER",
                    "Permanently deleted account: " + deletedName + " (" + deletedEmail + ")",
                    "USER", String.valueOf(id));
                return ResponseEntity.ok(Map.of("message", "Account deleted: " + deletedName));
            } catch (DataIntegrityViolationException e) {
                return ResponseEntity.status(409).body(Map.of("message",
                    "Cannot delete: " + deletedName + " has linked records. Use Disable to revoke login access instead."));
            }
        }).orElse(ResponseEntity.status(404).body(Map.of("message", "User not found")));
    }

    // ---------------------------------------------------------------
    // PATCH /api/users/{id}/change-password
    // User can change their own password (currentPassword required).
    // Super Admin can reset any user's password without currentPassword.
    // ---------------------------------------------------------------
    @PatchMapping("/{id}/change-password")
    public ResponseEntity<?> changePassword(
            @PathVariable Long id,
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        Long callerId = userIdFromHeader(authHeader);
        if (callerId == null) return ResponseEntity.status(401).body(Map.of("message", "Unauthorized"));

        boolean callerIsSuperAdmin = userRepository.findById(callerId)
            .map(u -> "SUPER_ADMIN".equals(u.getRole())).orElse(false);

        // Only allow changing own password unless Super Admin
        if (!callerId.equals(id) && !callerIsSuperAdmin)
            return ResponseEntity.status(403).body(Map.of("message", "You can only change your own password"));

        String newPassword = body.getOrDefault("newPassword", "").trim();
        if (newPassword.length() < 6)
            return ResponseEntity.badRequest().body(Map.of("message", "New password must be at least 6 characters"));

        return userRepository.findById(id).<ResponseEntity<?>>map(user -> {
            // Verify current password when changing own password (skip for Super Admin reset
            // and when the must_change_password flag is set — admin set a temporary password)
            if (callerId.equals(id)) {
                if (!user.isMustChangePassword()) {
                    String currentPassword = body.getOrDefault("currentPassword", "");
                    if (!passwordEncoder.matches(currentPassword, user.getPasswordHash()))
                        return ResponseEntity.badRequest().body(Map.of("message", "Current password is incorrect"));
                }
                // else: mustChangePassword=true → skip verification; admin set a temp password
            }
            user.setPasswordHash(passwordEncoder.encode(newPassword));
            user.setMustChangePassword(false);    // flag cleared after user sets own password
            userRepository.save(user);
            activityLogService.log(callerId, user.getFullName(), "CHANGE_PASSWORD",
                "Password changed for: " + user.getFullName(),
                "USER", String.valueOf(id));
            return ResponseEntity.ok(Map.of("message", "Password updated successfully"));
        }).orElse(ResponseEntity.status(404).body(Map.of("message", "User not found")));
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    /** Apply all non-credential profile fields from request body to entity. */
    private void applyProfile(User user, Map<String, String> body) {
        String fullName = trim(body.get("fullName"));
        if (fullName != null) user.setFullName(fullName);

        String empId = trim(body.get("employeeId"));
        user.setEmployeeId(empId != null && !empId.isBlank() ? empId : null);

        String username = trim(body.get("username"));
        user.setUsername(username != null && !username.isBlank() ? username : null);

        String designation = trim(body.get("designation"));
        user.setDesignation(designation != null && !designation.isBlank() ? designation : null);

        String birthdateStr = trim(body.get("birthdate"));
        if (birthdateStr != null && !birthdateStr.isBlank()) {
            try { user.setBirthdate(LocalDate.parse(birthdateStr)); } catch (Exception ignored) {}
        } else {
            user.setBirthdate(null);
        }

        String address = trim(body.get("address"));
        user.setAddress(address != null && !address.isBlank() ? address : null);

        String contact = trim(body.get("contactNumber"));
        user.setContactNumber(contact != null && !contact.isBlank() ? contact : null);

        String image = body.get("profileImage");
        if (image != null && !image.isBlank()) user.setProfileImage(image);
    }

    private static String trim(String s) { return s != null ? s.trim() : null; }

    private ResponseEntity<Map<String, String>> bad(String msg) {
        return ResponseEntity.badRequest().body(Map.of("message", msg));
    }

    private UserDto toDto(User u) {
        return new UserDto(
            u.getId(),
            u.getEmail(),
            u.getFullName(),
            u.getRole(),
            u.getStatus(),
            u.getEmployeeId(),
            u.getUsername(),
            u.getDesignation(),
            u.getBirthdate() != null ? u.getBirthdate().toString() : null,
            u.getAddress(),
            u.getContactNumber(),
            u.getProfileImage(),
            u.getLastLoginAt() != null ? u.getLastLoginAt().toString() : null,
            u.getAllowedPages(),
            u.isMustChangePassword()
        );
    }

    public record UserDto(
        Long    id,
        String  email,
        String  fullName,
        String  role,
        String  status,
        String  employeeId,
        String  username,
        String  designation,
        String  birthdate,
        String  address,
        String  contactNumber,
        String  profileImage,
        String  lastLoginAt,
        String  allowedPages,
        boolean mustChangePassword
    ) {}
}
