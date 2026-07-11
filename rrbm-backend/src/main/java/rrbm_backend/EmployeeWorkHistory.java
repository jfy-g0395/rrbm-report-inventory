package rrbm_backend;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "employee_work_history")
public class EmployeeWorkHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "employer_name", length = 150)
    private String employerName;

    @Column(name = "year_started", length = 10)
    private String yearStarted;

    @Column(name = "year_ended", length = 10)
    private String yearEnded;

    @Column(length = 100)
    private String position;
}
