package rrbm_backend;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * Records user actions for auditing and activity‑log purposes.
 * Each entry stores the acting user id, an action name, optional details, and a timestamp.
 */
@Data
@Entity
@Table(name = "activity_log")
public class ActivityLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 100)
    private String action;

    @Column(columnDefinition = "TEXT")
    private String details;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
