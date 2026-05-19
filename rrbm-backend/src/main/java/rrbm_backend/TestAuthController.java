package rrbm_backend;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/test-auth")
@CrossOrigin(origins = "*")
public class TestAuthController {
    
    private final UserRepository userRepository;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    
    public TestAuthController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }
    
    @GetMapping("/check-user")
    public Map<String, Object> checkUser() {
        var user = userRepository.findByEmail("admin@rrbm.com");

        Map<String, Object> result = new HashMap<>();
        result.put("userFound", user.isPresent());

        if (user.isPresent()) {
            String storedHash = user.get().getPasswordHash();
            String testPassword = "test123";
            
            result.put("email", user.get().getEmail());
            result.put("fullName", user.get().getFullName());
            result.put("role", user.get().getRole());
            result.put("storedHashLength", storedHash.length());
            result.put("storedHashFull", storedHash);
            result.put("testPassword", testPassword);
            result.put("testPasswordLength", testPassword.length());
            
            // Try matching
            boolean matches = encoder.matches(testPassword, storedHash);
            result.put("passwordMatches", matches);
            
            // Generate fresh hash of test123 and compare
            String freshHash = encoder.encode(testPassword);
            result.put("freshHashGenerated", freshHash);
            result.put("freshHashMatches", encoder.matches(testPassword, freshHash));
        }

        return result;
    }
    
    // ADD THIS NEW METHOD HERE:
    @GetMapping("/generate-hash")
    public Map<String, String> generateHash(@RequestParam String password) {
        String hash = encoder.encode(password);
        return Map.of(
            "password", password,
            "hash", hash,
            "matches", String.valueOf(encoder.matches(password, hash))
        );
    }
    @PostMapping("/reset-password")
    public Map<String, String> resetPassword(@RequestParam String email, @RequestParam String newPassword) {
        var userOpt = userRepository.findByEmail(email);
        
        if (userOpt.isEmpty()) {
            return Map.of("status", "error", "message", "User not found");
        }
        
        User user = userOpt.get();
        String newHash = encoder.encode(newPassword);
        user.setPasswordHash(newHash);
        userRepository.save(user);
        
        return Map.of(
            "status", "success",
            "message", "Password updated",
            "newHash", newHash
        );
    }
}