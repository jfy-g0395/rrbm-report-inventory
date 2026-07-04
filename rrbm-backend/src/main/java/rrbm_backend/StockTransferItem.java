package rrbm_backend;

import com.fasterxml.jackson.annotation.JsonBackReference;
import jakarta.persistence.*;

/**
 * One product line on a {@link StockTransfer}: move {@code quantity} of
 * {@code productId} from {@code fromWarehouse} to {@code toWarehouse}.
 * Warehouse codes are the canonical wh1/wh2/wh3 (wh3 = "Balagtas").
 */
@Entity
@Table(name = "stock_transfer_items")
public class StockTransferItem {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transfer_id", nullable = false)
    @JsonBackReference
    private StockTransfer transfer;

    @Column(name = "product_id")
    private Long productId;

    @Column(name = "product_code", length = 50)
    private String productCode;

    @Column(name = "product_name", nullable = false, length = 255)
    private String productName;

    @Column(name = "from_warehouse", nullable = false, length = 10)
    private String fromWarehouse;

    @Column(name = "to_warehouse", nullable = false, length = 10)
    private String toWarehouse;

    @Column(nullable = false)
    private int quantity;

    public Long getId() { return id; }
    public StockTransfer getTransfer() { return transfer; }
    public void setTransfer(StockTransfer transfer) { this.transfer = transfer; }
    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
    public String getProductCode() { return productCode; }
    public void setProductCode(String productCode) { this.productCode = productCode; }
    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }
    public String getFromWarehouse() { return fromWarehouse; }
    public void setFromWarehouse(String fromWarehouse) { this.fromWarehouse = fromWarehouse; }
    public String getToWarehouse() { return toWarehouse; }
    public void setToWarehouse(String toWarehouse) { this.toWarehouse = toWarehouse; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
}
