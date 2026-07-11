package rrbm_backend;

import jakarta.persistence.*;
import java.math.BigDecimal;

/**
 * A custom unit price for one product, negotiated for one reseller/distributor (S-A1).
 * When an order is created for that reseller, this price auto-fills the order line
 * (editable per the agreed behavior). Unmapped products fall back to the normal price.
 */
@Entity
@Table(name = "reseller_product_prices")
public class ResellerProductPrice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reseller_id", nullable = false)
    private Long resellerId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "unit_price", nullable = false, precision = 13, scale = 5)
    private BigDecimal unitPrice;

    public ResellerProductPrice() {}

    public ResellerProductPrice(Long resellerId, Long productId, BigDecimal unitPrice) {
        this.resellerId = resellerId;
        this.productId = productId;
        this.unitPrice = unitPrice;
    }

    public Long getId() { return id; }

    public Long getResellerId() { return resellerId; }
    public void setResellerId(Long resellerId) { this.resellerId = resellerId; }

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }

    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }
}
