package rrbm_backend;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "users")
public class User {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(unique = true, nullable = false, length = 120)
    private String email;
    
    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;
    
    @Column(name = "full_name", nullable = false, length = 120)
    private String fullName;
    
    @Column(length = 20, nullable = false)
    private String role = "STANDARD_USER";
    
    @Column(length = 20)
    private String status = "ACTIVE";

    // Employee profile fields
    @Column(name = "employee_id", length = 20, unique = true)
    private String employeeId;

    @Column(length = 80, unique = true)
    private String username;

    @Column(length = 100)
    private String designation;

    @Column
    private LocalDate birthdate;

    @Column(columnDefinition = "TEXT")
    private String address;

    @Column(name = "contact_number", length = 30)
    private String contactNumber;

    @Column(name = "profile_image", columnDefinition = "TEXT")
    private String profileImage;   // base64 data-URL

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    /**
     * JSON array of page keys this user is allowed to access.
     * e.g. '["orders","inventory","reports"]'
     * NULL = unrestricted (Super Admin bypass handled in code).
     */
    @Column(name = "allowed_pages", columnDefinition = "TEXT")
    private String allowedPages;

    /**
     * BCrypt hash of the employee's personal admin security key.
     * NULL = not yet assigned. Used to authorise Cancel, Refund, Void.
     */
    @Column(name = "admin_security_key", length = 255)
    private String adminSecurityKey;

    /**
     * When true the user must change their password on next login.
     * Set when a Super Admin creates or resets a password; cleared on own change.
     */
    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * True if this user is granted the given page/capability key.
     * Mirrors the frontend rule and {@link PageAccessInterceptor}: SUPER_ADMIN is unrestricted,
     * a null allowedPages is legacy-unrestricted, otherwise the JSON array must contain the key.
     * allowedPages is a JSON array of quoted keys, so a quoted-token match is exact here (our
     * keys are distinct and never substrings of one another).
     */
    public boolean hasPagePermission(String key) {
        if ("SUPER_ADMIN".equals(role)) return true;
        if (allowedPages == null) return true;
        return allowedPages.contains("\"" + key + "\"");
    }
}