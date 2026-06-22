package rrbm_backend;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "delivery_log")
public class DeliveryLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "receipt_number", unique = true, nullable = false)
    private String receiptNumber;

    @Column(name = "supplier_name", nullable = false)
    private String supplierName = "Unknown";

    @Column(name = "received_by", nullable = false) private String receivedBy;
    @Column(name = "verified_by") private String verifiedBy;
    @Column(name = "encoded_by_user_id") private Long encodedByUserId;
    @Column(name = "encoded_by_name") private String encodedByName;
    @Column(name = "total_items") private int totalItems;
    @Column(name = "total_quantity") private int totalQuantity;
    @Column(name = "notes") private String notes;
    @Column(name = "po_number") private String poNumber;
    @Column(name = "truck_plate") private String truckPlate;
    @Column(name = "driver_name") private String driverName;
    @Column(name = "report_date") private LocalDate reportDate;
    @Column(name = "created_at") private OffsetDateTime createdAt;
    @Column(name = "status", nullable = false) private String status = "RECEIVED";

    @OneToMany(mappedBy = "deliveryLog", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<DeliveryLogItem> items = new ArrayList<>();

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
        if (reportDate == null) reportDate = LocalDate.now();
    }

    // --- Getters & Setters ---
    public Long getId() { return id; }
    public String getReceiptNumber() { return receiptNumber; }
    public void setReceiptNumber(String receiptNumber) { this.receiptNumber = receiptNumber; }
    public String getSupplierName() { return supplierName; }
    public void setSupplierName(String supplierName) { this.supplierName = supplierName; }
    public String getReceivedBy() { return receivedBy; }
    public void setReceivedBy(String receivedBy) { this.receivedBy = receivedBy; }
    public String getVerifiedBy() { return verifiedBy; }
    public void setVerifiedBy(String verifiedBy) { this.verifiedBy = verifiedBy; }
    public Long getEncodedByUserId() { return encodedByUserId; }
    public void setEncodedByUserId(Long encodedByUserId) { this.encodedByUserId = encodedByUserId; }
    public String getEncodedByName() { return encodedByName; }
    public void setEncodedByName(String encodedByName) { this.encodedByName = encodedByName; }
    public int getTotalItems() { return totalItems; }
    public void setTotalItems(int totalItems) { this.totalItems = totalItems; }
    public int getTotalQuantity() { return totalQuantity; }
    public void setTotalQuantity(int totalQuantity) { this.totalQuantity = totalQuantity; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public String getPoNumber() { return poNumber; }
    public void setPoNumber(String poNumber) { this.poNumber = poNumber; }
    public String getTruckPlate() { return truckPlate; }
    public void setTruckPlate(String truckPlate) { this.truckPlate = truckPlate; }
    public String getDriverName() { return driverName; }
    public void setDriverName(String driverName) { this.driverName = driverName; }
    public LocalDate getReportDate() { return reportDate; }
    public void setReportDate(LocalDate reportDate) { this.reportDate = reportDate; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public List<DeliveryLogItem> getItems() { return items; }
    public void setItems(List<DeliveryLogItem> items) { this.items = items; }
}
