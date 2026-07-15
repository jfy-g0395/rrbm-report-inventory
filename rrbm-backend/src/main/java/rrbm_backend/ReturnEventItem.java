package rrbm_backend;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

/**
 * Per-line detail of a {@link ReturnEvent}: how many units of one order line were
 * returned, and of those how many were put back to stock (sellable) vs scrapped
 * (rejected). Sellable units go back to {@code restockWarehouse}.
 */
@Entity
@Table(name = "return_event_items")
public class ReturnEventItem {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "return_event_id", nullable = false)
    @JsonIgnore
    private ReturnEvent returnEvent;

    @Column(name = "order_item_id")
    private Long orderItemId;

    @Column(name = "product_id")
    private Long productId;

    @Column(name = "product_name", length = 200, nullable = false)
    private String productName;

    @Column(name = "returned_qty", nullable = false)
    private Integer returnedQty = 0;

    @Column(name = "sellable_qty", nullable = false)
    private Integer sellableQty = 0;

    @Column(name = "rejected_qty", nullable = false)
    private Integer rejectedQty = 0;

    @Column(name = "restock_warehouse", length = 10)
    private String restockWarehouse;

    // ── Getters / setters ────────────────────────────────────────────────────
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public ReturnEvent getReturnEvent() { return returnEvent; }
    public void setReturnEvent(ReturnEvent returnEvent) { this.returnEvent = returnEvent; }
    public Long getOrderItemId() { return orderItemId; }
    public void setOrderItemId(Long orderItemId) { this.orderItemId = orderItemId; }
    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }
    public Integer getReturnedQty() { return returnedQty; }
    public void setReturnedQty(Integer returnedQty) { this.returnedQty = returnedQty; }
    public Integer getSellableQty() { return sellableQty; }
    public void setSellableQty(Integer sellableQty) { this.sellableQty = sellableQty; }
    public Integer getRejectedQty() { return rejectedQty; }
    public void setRejectedQty(Integer rejectedQty) { this.rejectedQty = rejectedQty; }
    public String getRestockWarehouse() { return restockWarehouse; }
    public void setRestockWarehouse(String restockWarehouse) { this.restockWarehouse = restockWarehouse; }
}
