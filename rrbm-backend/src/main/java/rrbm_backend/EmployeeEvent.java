package rrbm_backend;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Append-only milestone timeline entry: salary/position/status changes (auto-logged
 * on update) and manual memos / contract addendums / notes.
 */
@Data
@Entity
@Table(name = "employee_events")
public class EmployeeEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "event_type", nullable = false, length = 30)
    private String eventType;   // SALARY_CHANGE/POSITION_CHANGE/STATUS_CHANGE/MEMO/ADDENDUM/NOTE

    @Column(name = "event_date", nullable = false)
    private LocalDate eventDate;

    @Column(name = "old_value", length = 150)
    private String oldValue;

    @Column(name = "new_value", length = 150)
    private String newValue;

    @Column(columnDefinition = "TEXT")
    private String details;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "created_by")
    private Long createdBy;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
        if (eventDate == null) eventDate = LocalDate.now();
    }
}
