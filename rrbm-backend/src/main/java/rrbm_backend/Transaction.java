package rrbm_backend;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Immutable accounting record. Every financial event in the system
 * (sale, refund, void, adjustment) maps to exactly one Transaction row.
 *
 * Rules:
 *   SALE         → positive amount  (order created)
 *   REFUND       → negative amount  (post-close partial/full refund)
 *   RETURN       → negative amount  (goods physically returned)
 *   VOID         → negative amount  (cancellation or post-close reversal)
 *   DISCOUNT     → negative amount  (order-level discount adjustment)
 *   ADJUSTMENT   → positive OR negative (manual accounting correction)
 *
 * Net sales for any period = SUM(amount) for that effective_date range.
 */
@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Human-readable unique code, e.g. SALE-210526-000001, REFUND-210526-000001-1716... */
    @Column(name = "transaction_code", length = 80, nullable = false, unique = true)
    private String transactionCode;

    /** Originating order — null for purely manual adjustments. */
    @Column(name = "order_id", length = 20)
    private String orderId;

    /** SALE | REFUND | RETURN | VOID | DISCOUNT | ADJUSTMENT */
    @Column(name = "transaction_type", length = 20, nullable = false)
    private String transactionType;

    /** Positive for revenue events, negative for reversal events. */
    @Column(precision = 12, scale = 2, nullable = false)
    private BigDecimal amount;

    /** ORDER | EXPENSE | MANUAL */
    @Column(name = "reference_type", length = 30)
    private String referenceType;

    /** orderId, expenseId, or any free-form reference. */
    @Column(name = "reference_id", length = 50)
    private String referenceId;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * The date this transaction counts towards for reporting.
     * For post-close entries this is the date of the reversal,
     * NOT the original order date — historical reports stay immutable.
     */
    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    @PrePersist
    protected void onCreate() {
        if (createdAt    == null) createdAt    = LocalDateTime.now();
        if (effectiveDate == null) effectiveDate = LocalDate.now();
    }

    // ── Getters & Setters ──────────────────────────────────────────
    public Long getId()                        { return id; }
    public String getTransactionCode()         { return transactionCode; }
    public void   setTransactionCode(String v) { this.transactionCode = v; }
    public String getOrderId()                 { return orderId; }
    public void   setOrderId(String v)         { this.orderId = v; }
    public String getTransactionType()         { return transactionType; }
    public void   setTransactionType(String v) { this.transactionType = v; }
    public BigDecimal getAmount()              { return amount; }
    public void   setAmount(BigDecimal v)      { this.amount = v; }
    public String getReferenceType()           { return referenceType; }
    public void   setReferenceType(String v)   { this.referenceType = v; }
    public String getReferenceId()             { return referenceId; }
    public void   setReferenceId(String v)     { this.referenceId = v; }
    public String getNotes()                   { return notes; }
    public void   setNotes(String v)           { this.notes = v; }
    public Long   getCreatedBy()               { return createdBy; }
    public void   setCreatedBy(Long v)         { this.createdBy = v; }
    public LocalDateTime getCreatedAt()        { return createdAt; }
    public LocalDate getEffectiveDate()        { return effectiveDate; }
    public void   setEffectiveDate(LocalDate v){ this.effectiveDate = v; }
}
