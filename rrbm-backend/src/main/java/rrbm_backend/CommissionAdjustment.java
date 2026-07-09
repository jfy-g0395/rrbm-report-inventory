package rrbm_backend;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "commission_adjustments")
public class CommissionAdjustment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "period_id", nullable = false)
    private Long periodId;

    @Column(name = "agent_id", nullable = false)
    private Long agentId;

    @Column(name = "adjustment_type", nullable = false, length = 20)
    private String adjustmentType;

    @Column(nullable = false, precision = 13, scale = 5)
    private BigDecimal amount;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "created_by")
    private Long createdBy;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }

    public Long getPeriodId() { return periodId; }
    public void setPeriodId(Long periodId) { this.periodId = periodId; }

    public Long getAgentId() { return agentId; }
    public void setAgentId(Long agentId) { this.agentId = agentId; }

    public String getAdjustmentType() { return adjustmentType; }
    public void setAdjustmentType(String adjustmentType) { this.adjustmentType = adjustmentType; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public OffsetDateTime getCreatedAt() { return createdAt; }

    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
}
