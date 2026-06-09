package rrbm_backend;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import rrbm_backend.dto.LoginRequest;
import rrbm_backend.dto.LoginResponse;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class AuthService {
    
    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;
    private final BCryptPasswordEncoder passwordEncoder;
    
    public AuthService(UserRepository userRepository, JwtUtil jwtUtil) {
        this.userRepository = userRepository;
        this.jwtUtil = jwtUtil;
        this.passwordEncoder = new BCryptPasswordEncoder();
    }
    
    /**
     * Authenticate user and return JWT token.
     * The request.getEmail() field accepts either an email address or a username.
     */
    public LoginResponse login(LoginRequest request) {
        String identifier = request.getEmail() != null ? request.getEmail().trim() : "";

        // Try by email first; fall back to username
        Optional<User> userOpt = userRepository.findByEmail(identifier);
        if (userOpt.isEmpty()) {
            userOpt = userRepository.findByUsername(identifier);
        }

        if (userOpt.isEmpty()) {
            throw new RuntimeException("Invalid email/username or password");
        }
        
        User user = userOpt.get();
        
        // Check password
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new RuntimeException("Invalid email or password");
        }

        // M-23: Reject DISABLED accounts before issuing a token.
        // AWAY is intentionally allowed — an away user may still need system access.
        if ("DISABLED".equals(user.getStatus())) {
            throw new RuntimeException("This account has been disabled. Contact your administrator.");
        }

        // Update last login time
        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);
        
        // Generate JWT token
        String token = jwtUtil.generateToken(user);
        
        // Build response
        LoginResponse.UserInfo userInfo = new LoginResponse.UserInfo(
            user.getId(),
            user.getEmail(),
            user.getFullName(),
            user.getRole(),
            user.getAllowedPages(),      // null for SUPER_ADMIN = no restrictions
            user.isMustChangePassword() // true → force password-change dialog on login
        );
        
        return new LoginResponse(token, userInfo);
    }

    /**
     * Verify an admin's own password without updating lastLoginAt.
     * The identifier can be either an email address or a username.
     * Used for COD order resume confirmation.
     */
    public boolean verifyPassword(String identifier, String password) {
        Optional<User> userOpt = userRepository.findByEmail(identifier);
        if (userOpt.isEmpty()) {
            userOpt = userRepository.findByUsername(identifier);
        }
        return userOpt
            .map(u -> passwordEncoder.matches(password, u.getPasswordHash()))
            .orElse(false);
    }
}