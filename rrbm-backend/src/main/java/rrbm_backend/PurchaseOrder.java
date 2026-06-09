package rrbm_backend;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "purchase_orders")
@Data
public class PurchaseOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "po_number", nullable = false, length = 20)
    private String poNumber;

    @Column(name = "vendor_name", nullable = false)
    private String vendorName;

    @Column(name = "vendor_contact")
    private String vendorContact;

    @Column(name = "vendor_address", columnDefinition = "TEXT")
    private String vendorAddress;

    @Column(name = "ship_to_name")
    private String shipToName;

    @Column(name = "ship_to_contact")
    private String shipToContact;

    @Column(name = "ship_to_address", columnDefinition = "TEXT")
    private String shipToAddress;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "vat_type", length = 20)
    private String vatType = "EXCLUSIVE";

    @Column(name = "shipping_arrangement", length = 100)
    private String shippingArrangement;

    @Column(name = "supplier_id")
    private Long supplierId;

    @Column(name = "vendor_reference", length = 50)
    private String vendorReference;

    @Column(length = 20)
    private String status = "INCOMPLETE";

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "total_amount", precision = 15, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @OneToMany(mappedBy = "purchaseOrder", cascade = CascadeType.ALL,
               fetch = FetchType.LAZY, orphanRemoval = true)
    @JsonIgnore
    private List<PoItem> items = new ArrayList<>();
}
