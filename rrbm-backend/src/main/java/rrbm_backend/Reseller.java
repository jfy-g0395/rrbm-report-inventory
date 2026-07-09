package rrbm_backend;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Reseller / Distributor registry entry (S-A1). Mirrors {@link Agent}, minus the
 * commission machinery. The {@code type} column distinguishes RESELLER vs DISTRIBUTOR
 * so both share one page and one registry.
 */
@Entity
@Table(name = "resellers")
public class Reseller {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reseller_code", nullable = false, unique = true, length = 20)
    private String resellerCode;

    @Column(nullable = false, length = 15)
    private String type;   // RESELLER | DISTRIBUTOR

    @Column(nullable = false, length = 150)
    private String name;

    @Column(name = "contact_person", nullable = false, length = 100)
    private String contactPerson;

    @Column(name = "contact_number", nullable = false, length = 50)
    private String contactNumber;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String address;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "delivery_days", length = 100)
    private String deliveryDays;   // CSV of MON..SUN

    @Column(name = "delivery_time_window", length = 50)
    private String deliveryTimeWindow;

    @Column(nullable = false, length = 20)
    private String status = "ACTIVE";

    @Column(name = "registration_date", nullable = false)
    private LocalDate registrationDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "created_by")
    private Long createdBy;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
        if (registrationDate == null) registrationDate = LocalDate.now();
    }

    public Long getId() { return id; }

    public String getResellerCode() { return resellerCode; }
    public void setResellerCode(String resellerCode) { this.resellerCode = resellerCode; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getContactPerson() { return contactPerson; }
    public void setContactPerson(String contactPerson) { this.contactPerson = contactPerson; }

    public String getContactNumber() { return contactNumber; }
    public void setContactNumber(String contactNumber) { this.contactNumber = contactNumber; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public String getDeliveryDays() { return deliveryDays; }
    public void setDeliveryDays(String deliveryDays) { this.deliveryDays = deliveryDays; }

    public String getDeliveryTimeWindow() { return deliveryTimeWindow; }
    public void setDeliveryTimeWindow(String deliveryTimeWindow) { this.deliveryTimeWindow = deliveryTimeWindow; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public LocalDate getRegistrationDate() { return registrationDate; }
    public void setRegistrationDate(LocalDate registrationDate) { this.registrationDate = registrationDate; }

    public OffsetDateTime getCreatedAt() { return createdAt; }

    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
}
