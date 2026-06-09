package rrbm_backend;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * Stores a BCrypt‑hashed master key used for privileged actions such as closing daily sales
 * or cancelling orders. Only admins with the appropriate role may create or rotate the key.
 */
@Data
@Entity
@Table(name = "master_keys")
public class MasterKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "key_hash", nullable = false, length = 255)
    private String keyHash;

    @Column(name = "label", length = 50)
    private String label;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_by")
    private Long updatedBy; // user id of the admin who last changed the key
}
