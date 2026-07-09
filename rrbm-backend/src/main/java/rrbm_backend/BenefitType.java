package rrbm_backend;

import jakarta.persistence.*;
import lombok.Data;

/** Admin-manageable catalog of benefit types (SSS, PhilHealth, HMO, …). */
@Data
@Entity
@Table(name = "benefit_types")
public class BenefitType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 80)
    private String name;

    @Column(name = "is_government")
    private Boolean isGovernment = false;

    @Column
    private Boolean active = true;
}
