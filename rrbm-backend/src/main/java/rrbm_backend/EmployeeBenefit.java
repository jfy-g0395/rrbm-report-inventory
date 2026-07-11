package rrbm_backend;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;

/** A benefit granted to an employee — checklist selection + optional amount/notes. */
@Data
@Entity
@Table(name = "employee_benefits")
public class EmployeeBenefit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "benefit_type_id", nullable = false)
    private Long benefitTypeId;

    @Column(precision = 13, scale = 5)
    private BigDecimal amount;

    @Column(length = 255)
    private String notes;
}
