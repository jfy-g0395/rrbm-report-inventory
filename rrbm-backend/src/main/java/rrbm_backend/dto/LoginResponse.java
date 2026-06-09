package rrbm_backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LoginResponse {
    private String token;
    private UserInfo user;
    
    @Data
    @AllArgsConstructor
    public static class UserInfo {
        private Long    id;
        private String  email;
        private String  fullName;
        private String  role;
        private String  allowedPages;        // JSON array string, null = unrestricted (Super Admin)
        private boolean mustChangePassword;  // true → force password change on first login
    }
}