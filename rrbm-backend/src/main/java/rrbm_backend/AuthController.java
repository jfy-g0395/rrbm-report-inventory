package rrbm_backend;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import rrbm_backend.dto.LoginRequest;
import rrbm_backend.dto.LoginResponse;
import rrbm_backend.ActivityLogService;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "*") // For now, allow all origins for testing
public class AuthController {
    
    private final AuthService authService;
    private final ActivityLogService activityLogService;
    
    public AuthController(AuthService authService, ActivityLogService activityLogService) {
        this.authService = authService;
        this.activityLogService = activityLogService;
    }
    
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        try {
            LoginResponse response = authService.login(request);
            activityLogService.log(response.getUser().getId(), "login", "User logged in");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorResponse(e.getMessage()));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(@RequestParam Long userId) {
        // In a real app you would invalidate the JWT on client side; here we just log.
        activityLogService.log(userId, "logout", "User logged out");
        return ResponseEntity.ok().build();
    }

    // Simple error response class
    record ErrorResponse(String message) {}
}