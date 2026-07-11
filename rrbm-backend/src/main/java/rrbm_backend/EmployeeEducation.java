package rrbm_backend;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "employee_education")
public class EmployeeEducation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(nullable = false, length = 20)
    private String level;   // PRIMARY/SECONDARY/TERTIARY/VOCATIONAL/GRADUATE

    @Column(name = "school_name", length = 150)
    private String schoolName;

    @Column(name = "year_graduated", length = 10)
    private String yearGraduated;
}
