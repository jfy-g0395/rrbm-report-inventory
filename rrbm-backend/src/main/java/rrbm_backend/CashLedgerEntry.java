package rrbm_backend;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Append-only cash-on-hand ledger row. Every physical cash movement is exactly
 * one row; cash on hand = SUM(amount) over the whole table.
 *
 * entry_type names the source of the movement; the SIGN of amount carries the
 * direction (+ inflow, - outflow):
 *   OPENING_BALANCE → positive  (one-time starting drawer count)
 *   ADD_CASH        → positive  (e.g. bank withdrawal added to the drawer)
 *   CASH_SALE       → positive  (order paid in cash); reversal row is negative
 *   CASH_EXPENSE    → negative  (expense paid in cash); reversal row is positive
 *   DEPOSIT         → negative  (cash deposited to the bank)
 *   ADJUSTMENT      → positive OR negative (manual reconciliation / returned change)
 */
@Entity
@Table(name = "cash_ledger")
public class CashLedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** OPENING_BALANCE | ADD_CASH | CASH_SALE | CASH_EXPENSE | DEPOSIT | ADJUSTMENT */
    @Column(name = "entry_type", length = 20, nullable = false)
    private String entryType;

    /** Signed: positive for inflow, negative for outflow. */
    @Column(precision = 17, scale = 5, nullable = false)
    private BigDecimal amount;

    /** Business date this movement counts towards. */
    @Column(name = "entry_date", nullable = false)
    private LocalDate entryDate;

    /** ORDER | EXPENSE | MANUAL */
    @Column(name = "reference_type", length = 20)
    private String referenceType;

    /** orderId, expenseId, or null for manual entries. */
    @Column(name = "reference_id", length = 50)
    private String referenceId;

    @Column(length = 500)
    private String note;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_by_name", length = 120)
    private String createdByName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (entryDate == null) entryDate = LocalDate.now();
    }

    // ── Getters & Setters ──────────────────────────────────────────
    public Long getId()                        { return id; }
    public String getEntryType()               { return entryType; }
    public void   setEntryType(String v)       { this.entryType = v; }
    public BigDecimal getAmount()              { return amount; }
    public void   setAmount(BigDecimal v)      { this.amount = v; }
    public LocalDate getEntryDate()            { return entryDate; }
    public void   setEntryDate(LocalDate v)    { this.entryDate = v; }
    public String getReferenceType()           { return referenceType; }
    public void   setReferenceType(String v)   { this.referenceType = v; }
    public String getReferenceId()             { return referenceId; }
    public void   setReferenceId(String v)     { this.referenceId = v; }
    public String getNote()                    { return note; }
    public void   setNote(String v)            { this.note = v; }
    public Long   getCreatedBy()               { return createdBy; }
    public void   setCreatedBy(Long v)         { this.createdBy = v; }
    public String getCreatedByName()           { return createdByName; }
    public void   setCreatedByName(String v)   { this.createdByName = v; }
    public LocalDateTime getCreatedAt()        { return createdAt; }
    public void   setCreatedAt(LocalDateTime v){ this.createdAt = v; }
}
