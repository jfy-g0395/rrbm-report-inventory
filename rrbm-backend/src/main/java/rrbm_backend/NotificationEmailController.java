package rrbm_backend;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/settings/notification-emails")
public class NotificationEmailController {

    private final NotificationEmailRepository repo;
    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;
    private final ActivityLogService activityLogService;

    public NotificationEmailController(NotificationEmailRepository repo,
                                        UserRepository userRepository,
                                        JwtUtil jwtUtil,
                                        ActivityLogService activityLogService) {
        this.repo               = repo;
        this.userRepository     = userRepository;
        this.jwtUtil            = jwtUtil;
        this.activityLogService = activityLogService;
    }

    private Long userId(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return null;
        try { return jwtUtil.extractUserId(authHeader.substring(7)); } catch (Exception e) { return null; }
    }

    private boolean isAdminOrSuper(Long uid) {
        if (uid == null) return false;
        return userRepository.findById(uid)
                .map(u -> "SUPER_ADMIN".equals(u.getRole()) || "ADMINISTRATOR".equals(u.getRole()))
                .orElse(false);
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestHeader(value = "Authorization", required = false) String auth) {
        Long uid = userId(auth);
        if (!isAdminOrSuper(uid))
            return ResponseEntity.status(403).body(Map.of("message", "Admin access required"));

        List<Map<String, Object>> emails = repo.findAll().stream().map(ne -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", ne.getId());
            m.put("email", ne.getEmail());
            m.put("createdAt", ne.getCreatedAt() != null ? ne.getCreatedAt().toString() : null);
            m.put("createdBy", ne.getCreatedBy());
            return m;
        }).collect(Collectors.toList());
        return ResponseEntity.ok(emails);
    }

    @PostMapping
    public ResponseEntity<?> add(@RequestBody Map<String, String> body,
                                  @RequestHeader(value = "Authorization", required = false) String auth) {
        Long uid = userId(auth);
        if (!isAdminOrSuper(uid))
            return ResponseEntity.status(403).body(Map.of("message", "Admin access required"));

        String email = (body.getOrDefault("email", "")).trim().toLowerCase();
        if (email.isBlank() || !email.contains("@"))
            return ResponseEntity.badRequest().body(Map.of("message", "Valid email is required"));
        if (repo.existsByEmail(email))
            return ResponseEntity.badRequest().body(Map.of("message", "Email already exists"));
        if (repo.count() >= 10)
            return ResponseEntity.badRequest().body(Map.of("message", "Maximum 10 notification emails allowed"));

        String createdBy = userRepository.findById(uid).map(User::getFullName).orElse("Admin");

        NotificationEmail ne = new NotificationEmail();
        ne.setEmail(email);
        ne.setCreatedBy(createdBy);
        repo.save(ne);

        activityLogService.log(uid, createdBy, "ADD_NOTIFICATION_EMAIL",
                "Added notification email: " + email, "SETTINGS", "notification_email");

        return ResponseEntity.ok(Map.of("message", "Email added", "id", ne.getId(), "email", ne.getEmail()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> remove(@PathVariable Long id,
                                     @RequestHeader(value = "Authorization", required = false) String auth) {
        Long uid = userId(auth);
        if (!isAdminOrSuper(uid))
            return ResponseEntity.status(403).body(Map.of("message", "Admin access required"));

        return repo.findById(id).map(ne -> {
            String email = ne.getEmail();
            repo.delete(ne);
            String deletedBy = userRepository.findById(uid).map(User::getFullName).orElse("Admin");
            activityLogService.log(uid, deletedBy, "REMOVE_NOTIFICATION_EMAIL",
                    "Removed notification email: " + email, "SETTINGS", "notification_email");
            return ResponseEntity.ok(Map.of("message", "Email removed"));
        }).orElse(ResponseEntity.status(404).body(Map.of("message", "Email not found")));
    }
}
