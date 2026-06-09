package rrbm_backend;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "delivery_log_items")
public class DeliveryLogItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "delivery_log_id")
    @JsonIgnore
    private DeliveryLog deliveryLog;

    @Column(name = "product_id") private Long productId;
    @Column(name = "product_name", nullable = false) private String productName;
    @Column(name = "quantity") private int quantity;
    @Column(name = "received_qty") private int receivedQty = 0;
    @Column(name = "rejected_qty") private int rejectedQty = 0;
    @Column(name = "unit_cost") private BigDecimal unitCost = BigDecimal.ZERO;
    @Column(name = "warehouse") private String warehouse = "wh1";

    public Long getId() { return id; }
    public DeliveryLog getDeliveryLog() { return deliveryLog; }
    public void setDeliveryLog(DeliveryLog deliveryLog) { this.deliveryLog = deliveryLog; }
    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
    public int getReceivedQty() { return receivedQty; }
    public void setReceivedQty(int receivedQty) { this.receivedQty = receivedQty; }
    public int getRejectedQty() { return rejectedQty; }
    public void setRejectedQty(int rejectedQty) { this.rejectedQty = rejectedQty; }
    public BigDecimal getUnitCost() { return unitCost; }
    public void setUnitCost(BigDecimal unitCost) { this.unitCost = unitCost; }
    public String getWarehouse() { return warehouse; }
    public void setWarehouse(String warehouse) { this.warehouse = warehouse; }
}
