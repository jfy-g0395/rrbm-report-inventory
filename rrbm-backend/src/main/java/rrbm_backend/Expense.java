package rrbm_backend;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a daily expense entry recorded by an admin.
 * Each expense has one or more line items (ExpenseItem).
 */
@Data
@Entity
@Table(name = "expenses")
public class Expense {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate date;

    @Column(name = "admin_id")
    private Long adminId;

    @Column(name = "admin_name", nullable = false, length = 150)
    private String adminName;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // ── Void / audit columns (V49) ─────────────────────────────────────────────
    // Voids are reversals — the original is never deleted. These columns record
    // who voided the entry, when, and why so the audit trail is preserved.

    @Column(name = "is_voided", nullable = false)
    private boolean voided = false;

    @Column(name = "voided_at")
    private OffsetDateTime voidedAt;

    /** FK to users.id — the user who authorised the void. NULL if not voided. */
    @Column(name = "voided_by")
    private Long voidedBy;

    @Column(name = "void_reason", columnDefinition = "TEXT")
    private String voidReason;

    // ── E2 fields (V51) ───────────────────────────────────────────────────────

    // Import tracking (V59 — CSV upload U-series)
    @Column(name = "is_imported", nullable = false)
    private boolean imported = false;

    @Column(name = "import_ref", columnDefinition = "TEXT")
    private String importRef;

    @Column(name = "late_imported", nullable = false)
    private boolean lateImported = false;

    @Column(name = "payment_method", length = 30)
    private String paymentMethod;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "reference_number", length = 100)
    private String referenceNumber;

    @Column(nullable = false, length = 20)
    private String status = "COMPLETED";

    @OneToMany(mappedBy = "expense", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ExpenseItem> items = new ArrayList<>();

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    /** Recalculate totalAmount from items before saving. */
    public void recalculateTotal() {
        totalAmount = items.stream()
                .map(i -> i.getAmount() != null ? i.getAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
