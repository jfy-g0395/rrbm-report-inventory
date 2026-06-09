package rrbm_backend;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Entity
@Table(name = "agent_commissions")
public class AgentCommission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "agent_id", nullable = false)
    private Long agentId;

    @Column(name = "period_id", nullable = false)
    private Long periodId;

    @Column(name = "period_code", nullable = false, length = 30)
    private String periodCode;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "total_op", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalOp = BigDecimal.ZERO;

    @Column(name = "total_bonus", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalBonus = BigDecimal.ZERO;

    @Column(name = "total_deduction", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalDeduction = BigDecimal.ZERO;

    @Column(name = "net_commission", nullable = false, precision = 10, scale = 2)
    private BigDecimal netCommission = BigDecimal.ZERO;

    @Column(name = "released_at", nullable = false)
    private OffsetDateTime releasedAt;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "RELEASED";

    @Column(name = "payment_method", length = 50)
    private String paymentMethod;

    @Column(name = "payment_reference", length = 100)
    private String paymentReference;

    @Column(name = "payment_date")
    private LocalDate paymentDate;

    @Column(name = "paid_by")
    private Long paidBy;

    @Column(name = "paid_at")
    private OffsetDateTime paidAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }

    public Long getId()                          { return id; }

    public Long getAgentId()                     { return agentId; }
    public void setAgentId(Long agentId)         { this.agentId = agentId; }

    public Long getPeriodId()                    { return periodId; }
    public void setPeriodId(Long periodId)       { this.periodId = periodId; }

    public String getPeriodCode()                { return periodCode; }
    public void setPeriodCode(String periodCode) { this.periodCode = periodCode; }

    public LocalDate getStartDate()              { return startDate; }
    public void setStartDate(LocalDate startDate){ this.startDate = startDate; }

    public LocalDate getEndDate()                { return endDate; }
    public void setEndDate(LocalDate endDate)    { this.endDate = endDate; }

    public BigDecimal getTotalOp()               { return totalOp; }
    public void setTotalOp(BigDecimal totalOp)   { this.totalOp = totalOp; }

    public BigDecimal getTotalBonus()            { return totalBonus; }
    public void setTotalBonus(BigDecimal totalBonus) { this.totalBonus = totalBonus; }

    public BigDecimal getTotalDeduction()        { return totalDeduction; }
    public void setTotalDeduction(BigDecimal totalDeduction) { this.totalDeduction = totalDeduction; }

    public BigDecimal getNetCommission()         { return netCommission; }
    public void setNetCommission(BigDecimal netCommission) { this.netCommission = netCommission; }

    public OffsetDateTime getReleasedAt()        { return releasedAt; }
    public void setReleasedAt(OffsetDateTime releasedAt) { this.releasedAt = releasedAt; }

    public String getStatus()                    { return status; }
    public void setStatus(String status)         { this.status = status; }

    public String getPaymentMethod()             { return paymentMethod; }
    public void setPaymentMethod(String v)       { this.paymentMethod = v; }

    public String getPaymentReference()          { return paymentReference; }
    public void setPaymentReference(String v)    { this.paymentReference = v; }

    public LocalDate getPaymentDate()            { return paymentDate; }
    public void setPaymentDate(LocalDate v)      { this.paymentDate = v; }

    public Long getPaidBy()                      { return paidBy; }
    public void setPaidBy(Long v)                { this.paidBy = v; }

    public OffsetDateTime getPaidAt()            { return paidAt; }
    public void setPaidAt(OffsetDateTime v)      { this.paidAt = v; }

    public OffsetDateTime getCreatedAt()         { return createdAt; }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",               id);
        m.put("agentId",          agentId);
        m.put("periodId",         periodId);
        m.put("periodCode",       periodCode);
        m.put("startDate",        startDate  != null ? startDate.toString()  : null);
        m.put("endDate",          endDate    != null ? endDate.toString()    : null);
        m.put("totalOp",          totalOp);
        m.put("totalBonus",       totalBonus);
        m.put("totalDeduction",   totalDeduction);
        m.put("netCommission",    netCommission);
        m.put("status",           status);
        m.put("releasedAt",       releasedAt  != null ? releasedAt.toString()  : null);
        m.put("paymentMethod",    paymentMethod);
        m.put("paymentReference", paymentReference);
        m.put("paymentDate",      paymentDate != null ? paymentDate.toString() : null);
        m.put("paidBy",           paidBy);
        m.put("paidAt",           paidAt      != null ? paidAt.toString()      : null);
        return m;
    }
}
