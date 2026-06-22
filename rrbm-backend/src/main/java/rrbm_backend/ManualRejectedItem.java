package rrbm_backend;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A manually-entered rejected item — for "unrecorded" rejections that did not
 * come from a delivery receipt or an order void/return. Record-only: does NOT
 * affect inventory stock. Editable/deletable by accounting + super-admin only.
 */
@Entity
@Table(name = "manual_rejected_items")
public class ManualRejectedItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "report_date", nullable = false) private LocalDate reportDate;
    @Column(name = "product_id") private Long productId;
    @Column(name = "product_name", nullable = false) private String productName;
    @Column(name = "rejected_qty", nullable = false) private int rejectedQty;
    @Column(name = "reason") private String reason;
    @Column(name = "created_by") private String createdBy;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (reportDate == null) reportDate = LocalDate.now();
    }

    public Long getId() { return id; }
    public LocalDate getReportDate() { return reportDate; }
    public void setReportDate(LocalDate reportDate) { this.reportDate = reportDate; }
    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }
    public int getRejectedQty() { return rejectedQty; }
    public void setRejectedQty(int rejectedQty) { this.rejectedQty = rejectedQty; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
