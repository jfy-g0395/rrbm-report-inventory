package rrbm_backend;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "commission_entries")
public class CommissionEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "period_id", nullable = false)
    private Long periodId;

    @Column(name = "agent_id", nullable = false)
    private Long agentId;

    @Column(name = "order_id", length = 20)
    private String orderId;

    @Column(name = "order_item_id")
    private Long orderItemId;

    @Column(name = "order_date", nullable = false)
    private LocalDate orderDate;

    @Column(name = "product_name", length = 200)
    private String productName;

    @Column(nullable = false)
    private Integer quantity = 1;

    @Column(name = "base_price", precision = 10, scale = 2)
    private BigDecimal basePrice;

    @Column(name = "op_rate", precision = 5, scale = 4)
    private BigDecimal opRate;

    @Column(name = "op_per_unit", precision = 10, scale = 2)
    private BigDecimal opPerUnit;

    @Column(name = "op_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal opAmount;

    @Column(nullable = false, length = 20)
    private String status = "PENDING";

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }

    public Long getPeriodId() { return periodId; }
    public void setPeriodId(Long periodId) { this.periodId = periodId; }

    public Long getAgentId() { return agentId; }
    public void setAgentId(Long agentId) { this.agentId = agentId; }

    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }

    public Long getOrderItemId() { return orderItemId; }
    public void setOrderItemId(Long orderItemId) { this.orderItemId = orderItemId; }

    public LocalDate getOrderDate() { return orderDate; }
    public void setOrderDate(LocalDate orderDate) { this.orderDate = orderDate; }

    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }

    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }

    public BigDecimal getBasePrice() { return basePrice; }
    public void setBasePrice(BigDecimal basePrice) { this.basePrice = basePrice; }

    public BigDecimal getOpRate() { return opRate; }
    public void setOpRate(BigDecimal opRate) { this.opRate = opRate; }

    public BigDecimal getOpPerUnit() { return opPerUnit; }
    public void setOpPerUnit(BigDecimal opPerUnit) { this.opPerUnit = opPerUnit; }

    public BigDecimal getOpAmount() { return opAmount; }
    public void setOpAmount(BigDecimal opAmount) { this.opAmount = opAmount; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
}
