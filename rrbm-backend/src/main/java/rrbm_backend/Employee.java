package rrbm_backend;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Employee 201 record (HR). Distinct from {@link User} (system logins) — this is
 * personnel data. Child records (education, work history, benefits, events) live in
 * their own tables and are managed by {@code EmployeeController}.
 */
@Data
@Entity
@Table(name = "employees")
public class Employee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_code", unique = true, length = 20)
    private String employeeCode;

    @Column(name = "last_name", nullable = false, length = 80)
    private String lastName;

    @Column(name = "first_name", nullable = false, length = 80)
    private String firstName;

    @Column(name = "middle_name", length = 80)
    private String middleName;

    @Column(name = "maiden_name", length = 80)
    private String maidenName;

    @Column(nullable = false)
    private LocalDate birthdate;

    @Column(length = 50)
    private String nationality;

    @Column(name = "civil_status", length = 20)
    private String civilStatus;

    @Column(length = 20)
    private String gender;

    @Column(nullable = false, length = 100)
    private String position;

    @Column(name = "date_of_employment", nullable = false)
    private LocalDate dateOfEmployment;

    @Column(length = 150)
    private String email;

    @Column(name = "spouse_name", length = 150)
    private String spouseName;

    @Column(name = "contact_number", nullable = false, length = 50)
    private String contactNumber;

    @Column(columnDefinition = "TEXT")
    private String address;

    @Column(name = "sss_number", length = 30)
    private String sssNumber;

    @Column(name = "pagibig_number", length = 30)
    private String pagibigNumber;

    @Column(name = "philhealth_number", length = 30)
    private String philhealthNumber;

    @Column(columnDefinition = "TEXT")
    private String photo;   // base64 data-URL (2x2)

    @Column(name = "employment_status", nullable = false, length = 20)
    private String employmentStatus = "PROBATIONARY";

    @Column(name = "probation_end_date")
    private LocalDate probationEndDate;

    @Column(name = "daily_wage", precision = 13, scale = 5)
    private BigDecimal dailyWage;

    @Column(nullable = false, length = 20)
    private String status = "ACTIVE";

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "created_by")
    private Long createdBy;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
