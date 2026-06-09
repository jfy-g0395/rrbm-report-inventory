package rrbm_backend;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * GET  /api/settings         — returns all key→value pairs visible to the frontend
 * POST /api/settings         — updates a specific subset of editable keys
 */
@RestController
@RequestMapping("/api/settings")
@CrossOrigin(origins = "*")
public class SettingsController {

    // Only these keys may be changed via the Settings page UI
    private static final Set<String> EDITABLE_KEYS = Set.of(
            "company_name", "company_address", "company_contact", "daily_reset_time"
    );

    private final SettingsRepository settingsRepository;
    private final JwtUtil jwtUtil;

    public SettingsController(SettingsRepository settingsRepository, JwtUtil jwtUtil) {
        this.settingsRepository = settingsRepository;
        this.jwtUtil = jwtUtil;
    }

    /** Return all settings as a flat key→value map (excludes sensitive hashes). */
    @GetMapping
    public ResponseEntity<Map<String, String>> getSettings() {
        Map<String, String> result = new LinkedHashMap<>();
        settingsRepository.findAll().forEach(s -> {
            // Never expose hashes or internal keys to the frontend
            if (!s.getKeyName().contains("hash") && !s.getKeyName().contains("secret")) {
                result.put(s.getKeyName(), s.getValue());
            }
        });
        return ResponseEntity.ok(result);
    }

    /** Update editable settings. Body is a flat key→value map. */
    @PostMapping
    public ResponseEntity<?> updateSettings(@RequestBody Map<String, String> updates,
                                             @RequestHeader("Authorization") String authHeader) {
        Long userId = extractUserId(authHeader);

        for (Map.Entry<String, String> entry : updates.entrySet()) {
            String key = entry.getKey();
            if (!EDITABLE_KEYS.contains(key)) continue; // silently skip non-editable keys

            settingsRepository.findById(key).ifPresent(s -> {
                s.setValue(entry.getValue() != null ? entry.getValue().trim() : "");
                s.setUpdatedAt(LocalDateTime.now());
                s.setUpdatedBy(userId);
                settingsRepository.save(s);
            });
        }
        return ResponseEntity.ok(Map.of("message", "Settings saved"));
    }

    private Long extractUserId(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return null;
        try { return jwtUtil.extractUserId(authHeader.substring(7)); } catch (Exception e) { return null; }
    }
}
