package rrbm_backend;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

// Maps to the inventory_movements table.
// Every time stock changes — order placed, order cancelled, manual adjustment —
// we write one row here per item per warehouse. This gives a full audit trail.

@Data
@Entity
@Table(name = "inventory_movements")
public class InventoryMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Which product was affected
    @Column(name = "product_id", nullable = false)
    private Long productId;

    // Type of movement — matches DB constraint:
    // ORDER_OUT, CANCELLED_RETURN, MANUAL_ADJUST, RESTOCK, TRANSFER
    @Column(name = "movement_type", nullable = false, length = 30)
    private String movementType;

    // Which warehouse: wh1, wh2, wh3
    @Column(nullable = false, length = 10)
    private String warehouse;

    // Positive = stock added, Negative = stock removed
    // e.g. ORDER_OUT of 100 pcs → quantity = -100
    // CANCELLED_RETURN of 100 pcs → quantity = +100
    @Column(nullable = false)
    private Integer quantity;

    // The order ID or reference that triggered this movement
    @Column(name = "reference_id", length = 50)
    private String referenceId;

    // Human-readable reason
    @Column(length = 255)
    private String reason;

    // Who triggered this (user ID)
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
