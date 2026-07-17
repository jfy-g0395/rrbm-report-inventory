package rrbm_backend;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "po_items")
public class PoItem {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "po_id", nullable = false)
    @JsonIgnore
    private PurchaseOrder purchaseOrder;

    @Column(name = "item_description", length = 500, nullable = false)
    private String itemDescription;

    @Column(name = "quantity_ordered")
    private Integer quantityOrdered = 1;

    @Column(name = "unit_price", precision = 18, scale = 5)
    private BigDecimal unitPrice = BigDecimal.ZERO;

    @Column(name = "line_total", precision = 18, scale = 5)
    private BigDecimal lineTotal = BigDecimal.ZERO;

    @Column(name = "fulfilled_qty")
    private Integer fulfilledQty = 0;

    @Column(name = "dr_number", length = 100)
    private String drNumber;

    @Column(name = "is_fulfilled")
    private Boolean isFulfilled = false;

    @Column(name = "supplier_item_code", length = 50)
    private String supplierItemCode;

    @Column(name = "supplier_description", columnDefinition = "TEXT")
    private String supplierDescription;

    @Column(name = "product_id")
    private Long productId;

    @Column(name = "is_final_delivery")
    private Boolean isFinalDelivery = false;

    // ── Getters & Setters ─────────────────────────────────────────────────

    public Long getId()                        { return id; }
    public PurchaseOrder getPurchaseOrder()    { return purchaseOrder; }
    public void setPurchaseOrder(PurchaseOrder po) { this.purchaseOrder = po; }
    public String getItemDescription()         { return itemDescription; }
    public void setItemDescription(String v)   { this.itemDescription = v; }
    public Integer getQuantityOrdered()        { return quantityOrdered; }
    public void setQuantityOrdered(Integer v)  { this.quantityOrdered = v; }
    public BigDecimal getUnitPrice()           { return unitPrice; }
    public void setUnitPrice(BigDecimal v)     { this.unitPrice = v; }
    public BigDecimal getLineTotal()           { return lineTotal; }
    public void setLineTotal(BigDecimal v)     { this.lineTotal = v; }
    public Integer getFulfilledQty()           { return fulfilledQty; }
    public void setFulfilledQty(Integer v)     { this.fulfilledQty = v; }
    public String getDrNumber()                { return drNumber; }
    public void setDrNumber(String v)          { this.drNumber = v; }
    public Boolean getIsFulfilled()            { return isFulfilled; }
    public void setIsFulfilled(Boolean v)      { this.isFulfilled = v; }
    public String getSupplierItemCode()        { return supplierItemCode; }
    public void setSupplierItemCode(String v)  { this.supplierItemCode = v; }
    public String getSupplierDescription()     { return supplierDescription; }
    public void setSupplierDescription(String v) { this.supplierDescription = v; }
    public Long getProductId()                 { return productId; }
    public void setProductId(Long v)           { this.productId = v; }
    public Boolean getIsFinalDelivery()        { return isFinalDelivery; }
    public void setIsFinalDelivery(Boolean v)  { this.isFinalDelivery = v; }
}
