package rrbm_backend;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Persisted per-day expense snapshot ("Expense Log"), created/refreshed at daily close.
 * {@code snapshotJson} holds the day's expense entries plus category / payment-method
 * breakdowns so the Expense page can render the full log without recomputing (V89).
 */
@Entity
@Table(name = "daily_expense_logs")
public class DailyExpenseLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "report_date", nullable = false, unique = true)
    private LocalDate reportDate;

    @Column(name = "total_amount", nullable = false, precision = 15, scale = 5)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(name = "entry_count", nullable = false)
    private int entryCount = 0;

    @Column(name = "snapshot_json", nullable = false, columnDefinition = "TEXT")
    private String snapshotJson = "{}";

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "created_by")
    private Long createdBy;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public LocalDate getReportDate() { return reportDate; }
    public void setReportDate(LocalDate reportDate) { this.reportDate = reportDate; }

    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }

    public int getEntryCount() { return entryCount; }
    public void setEntryCount(int entryCount) { this.entryCount = entryCount; }

    public String getSnapshotJson() { return snapshotJson; }
    public void setSnapshotJson(String snapshotJson) { this.snapshotJson = snapshotJson; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
}
